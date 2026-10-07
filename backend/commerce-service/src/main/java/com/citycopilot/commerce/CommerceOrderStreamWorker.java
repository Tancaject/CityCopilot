package com.citycopilot.commerce;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_COMPENSATION_REQUIRED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_DEAD_LETTERED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_ORDER_CREATED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_RECEIVED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_REPAIR_REQUIRED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_RESTORED;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.PHASE_RESTORE_PENDING;
import static com.citycopilot.commerce.CommerceOrderRecoveryStore.LostRecoveryClaim;
import static com.citycopilot.commerce.CommerceRedisKeyspace.ORDER_CONSUMER_GROUP;

/** Stream/PEL 消费和持久恢复协调；仅在数据库终态成立后清理 Redis 在途与消息。 */
@Service
public class CommerceOrderStreamWorker {
    private static final Logger log = LoggerFactory.getLogger(CommerceOrderStreamWorker.class);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final CommerceAdmissionDurabilityService durableAdmissions;
    private final CommerceRedisKeyspace keyspace;
    private final CommerceOrderService orders;
    private final CommerceOrderAdmissionService admissions;
    private final CommerceOrderRecoveryStore recovery;
    private final String consumerName;
    private final boolean streamEnabled;
    private final int streamBatchSize;
    private final int streamMaxDeliveries;
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_compensate.lua");
    private static final DefaultRedisScript<Long> FINALIZE_ORDER_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_finalize_order.lua");
    private static final DefaultRedisScript<Long> ACK_DELETE_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_ack_delete.lua");
    private static final Duration PENDING_MIN_IDLE = Duration.ofSeconds(30);
    /** 只在读取开始失败和恢复时各记一次日志，避免 50ms 轮询在故障期间刷屏。 */
    private volatile boolean streamReadFailing;

    public CommerceOrderStreamWorker(JdbcTemplate jdbc, StringRedisTemplate redis, TransactionTemplate transactions,
            ObjectMapper objectMapper, CommerceAdmissionDurabilityService durableAdmissions,
            CommerceRedisKeyspace keyspace,
            CommerceOrderService orders, CommerceOrderAdmissionService admissions, CommerceOrderRecoveryStore recovery,
            @Value("${citycopilot.commerce.consumer-name:commerce-local}") String consumerName,
            @Value("${citycopilot.commerce.stream-worker-enabled:${citycopilot.commerce.stream-enabled:true}}") boolean streamEnabled,
            @Value("${citycopilot.commerce.stream-batch-size:100}") int streamBatchSize,
            @Value("${citycopilot.commerce.stream-max-deliveries:10}") int streamMaxDeliveries) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.durableAdmissions = durableAdmissions;
        this.keyspace = keyspace;
        this.orders = orders;
        this.admissions = admissions;
        this.recovery = recovery;
        this.consumerName = consumerName + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.streamEnabled = streamEnabled;
        this.streamBatchSize = Math.max(1, streamBatchSize);
        this.streamMaxDeliveries = Math.max(1, streamMaxDeliveries);
    }

    @Scheduled(fixedDelayString = "${citycopilot.commerce.admission-recovery-poll-ms:1000}",
            initialDelayString = "${citycopilot.commerce.admission-recovery-poll-ms:1000}")
    public void recoverDurableAdmissions() {
        if (streamEnabled) admissions.recoverDurableAdmissions();
    }

    /**
     * 为每个逻辑分片建立消费组；临时 bootstrap 记录解决空 Stream 无法直接建组的问题。
     */
    @PostConstruct
    void initializeStream() {
        if (!streamEnabled) return;
        for (String stream : keyspace.streams()) {
            RecordId bootstrap = null;
            try {
                bootstrap = redis.opsForStream().add(stream, Map.of("bootstrap", "true"));
                redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), ORDER_CONSUMER_GROUP);
            } catch (RuntimeException failure) {
                if (!isBusyGroup(failure)) {
                    throw failure;
                }
            } finally {
                if (bootstrap != null) {
                    try {
                        redis.opsForStream().delete(stream, bootstrap);
                    } catch (RuntimeException cleanupFailure) {
                        // 残留 bootstrap 记录无业务副作用，普通 Worker 会 ACK；不要用清理异常掩盖初始化结果。
                        log.warn("Unable to remove Commerce stream bootstrap record {}", bootstrap);
                    }
                }
            }
        }
    }

    /**
     * 轮询各逻辑分片的新 Stream 记录并逐条推进可恢复数据库事务。
     *
     * <p>批量读取只减少 Redis 往返；每条消息仍独立处理，单条失败保留在 PEL。</p>
     */
    @Scheduled(fixedDelayString = "${citycopilot.commerce.stream-poll-ms:50}")
    public void consumeOrders() {
        if (!streamEnabled) return;
        // Redis Cluster 不能在一次 XREADGROUP 中跨 slot 读取，因此每个逻辑分片 Stream 独立轮询。
        for (String stream : keyspace.streams()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(ORDER_CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().count(streamBatchSize),
                        StreamOffset.create(stream, ReadOffset.lastConsumed())
                );
                if (streamReadFailing) {
                    streamReadFailing = false;
                    log.info("Commerce shard stream {} is readable again", stream);
                }
                if (records == null) continue;
                for (MapRecord<String, Object, Object> record : records) {
                    processSafely(record, 1L);
                }
            } catch (Exception unavailable) {
                if (!streamReadFailing) {
                    streamReadFailing = true;
                    log.warn("Commerce shard stream {} is unavailable; new orders are not being consumed", stream, unavailable);
                }
            }
        }
    }

    /** 扫描全部分片 PEL，并把长时间空闲的 Pending 消息转交给当前 Worker。 */
    @Scheduled(
            fixedDelayString = "${citycopilot.commerce.pending-reclaim-ms:5000}",
            initialDelayString = "${citycopilot.commerce.pending-reclaim-ms:5000}"
    )
    public void reclaimPendingOrders() {
        if (!streamEnabled) return;
        for (String stream : keyspace.streams()) {
            reclaimPendingStream(stream);
        }
    }

    /**
     * 分页扫描单个 Stream 的完整 PEL，以 XCLAIM 接管超时消息并保留累计投递次数。
     */
    private void reclaimPendingStream(String stream) {
        try {
            Range<String> scanRange = Range.unbounded();
            String previousLastId = null;
            while (true) {
                PendingMessages pending = redis.opsForStream().pending(
                        stream,
                        ORDER_CONSUMER_GROUP,
                        scanRange,
                        streamBatchSize
                );
                if (pending == null || pending.isEmpty()) return;

                List<RecordId> stale = new ArrayList<>();
                Map<String, Long> deliveryCounts = new LinkedHashMap<>();
                String lastId = null;
                for (PendingMessage message : pending) {
                    lastId = message.getIdAsString();
                    if (message.getElapsedTimeSinceLastDelivery().compareTo(PENDING_MIN_IDLE) >= 0) {
                        stale.add(message.getId());
                        deliveryCounts.put(
                                message.getIdAsString(),
                                Math.max(1L, message.getTotalDeliveryCount() + 1L)
                        );
                    }
                }

                if (!stale.isEmpty()) {
                    // XCLAIM 只改变 PEL 所有者，不删除消息；后续仍走与新消息相同的幂等处理链路。
                    List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                            stream,
                            ORDER_CONSUMER_GROUP,
                            consumerName,
                            PENDING_MIN_IDLE,
                            stale.toArray(RecordId[]::new)
                    );
                    if (claimed != null) {
                        for (MapRecord<String, Object, Object> record : claimed) {
                            processSafely(
                                    record,
                                    deliveryCounts.getOrDefault(record.getId().getValue(), 1L)
                            );
                        }
                    }
                }

                if (pending.size() < streamBatchSize || lastId == null) return;
                if (lastId.equals(previousLastId)) {
                    log.warn("Commerce pending scan did not advance beyond {}; stopping this pass", lastId);
                    return;
                }
                previousLastId = lastId;
                scanRange = Range.rightUnbounded(Range.Bound.exclusive(lastId));
            }
        } catch (Exception unavailable) {
            log.warn(
                    "Unable to reclaim pending Commerce orders from {}: {}",
                    stream,
                    unavailable.getMessage()
            );
        }
    }

    /**
     * 包装单条 Stream 处理；未达投递上限时保留 Pending，达到上限后进入毒消息终态流程。
     */
    void processSafely(MapRecord<String, Object, Object> record, long deliveryCount) {
        try {
            process(record);
        } catch (RuntimeException malformed) {
            if (deliveryCount >= streamMaxDeliveries) {
                deadLetterPoisonRecord(record, deliveryCount, malformed);
                return;
            }
            log.error(
                    "Unable to process Commerce order record {} after delivery {}; leaving it pending",
                    record.getId().getValue(),
                    deliveryCount,
                    malformed
            );
        }
    }

    /**
     * 对超过投递上限的消息建立可审计终态；无法证明身份时不执行库存补偿。
     */
    private void deadLetterPoisonRecord(
            MapRecord<String, Object, Object> record,
            long deliveryCount,
            RuntimeException failure
    ) {
        String recordId = record.getId().getValue();
        String stream = recordStream(record);
        String sourceId = keyspace.recoverySourceId(stream, recordId);
        PoisonIdentity identity = poisonIdentity(record);
        try {
            if (identity != null && keyspace.stream(identity.voucherId()).equals(stream)) {
                terminalizeIdentifiedPoison(
                        record,
                        sourceId,
                        stream,
                        recordId,
                        identity,
                        deliveryCount,
                        failure
                );
            } else {
                String message = poisonMessage(
                        deliveryCount,
                        "operator_repair_required; identity_unproven",
                        failure
                );
                persistDeadLetter(sourceId, record.getValue(), message);
                // 身份不可信时只能持久化原载荷并 ACK/删除，不能猜测用户或 Voucher 做补偿。
                Long acknowledged = acknowledgeAndDelete(stream, recordId);
                if (acknowledged == null || acknowledged != 1L) {
                    throw new IllegalStateException("Redis did not acknowledge poison stream record");
                }
            }
            log.error(
                    "Dead-lettered poison Commerce stream record {} after {} deliveries",
                    sourceId,
                    deliveryCount,
                    failure
            );
        } catch (RuntimeException terminalFailure) {
            log.error(
                    "Unable to durably dead-letter poison Commerce stream record {}; leaving it pending",
                    sourceId,
                    terminalFailure
            );
        }
    }

    /**
     * 对身份可证明的毒消息，根据订单、持久准入、预留和 Recovery 阶段选择安全终态。
     *
     * <p>已有订单只补终态；持久 ACCEPTED 保留业务承诺并转人工修复；仅在没有这些事实时才补偿
     * Redis 准入。补偿无法确认则记录 compensation_delta，禁止假装库存已恢复。</p>
     */
    private void terminalizeIdentifiedPoison(
            MapRecord<String, Object, Object> record,
            String sourceId,
            String stream,
            String recordId,
            PoisonIdentity identity,
            long deliveryCount,
            RuntimeException failure
    ) {
        ensureRecoveryRecord(
                sourceId,
                identity.orderId(),
                identity.voucherId(),
                identity.userId(),
                identity.idempotencyKey(),
                record.getValue()
        );
        Integer durableOrder = jdbc.queryForObject("""
                SELECT COUNT(*) FROM commerce_order
                WHERE id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                """, Integer.class, identity.orderId(), identity.userId(),
                identity.voucherId(), identity.idempotencyKey());
        if (durableOrder != null && durableOrder == 1) {
            durableAdmissions.markOrderCreated(
                    identity.orderId(), identity.voucherId(), identity.userId(),
                    identity.idempotencyKey()
            );
            String message = poisonMessage(
                    deliveryCount,
                    "durable_order_created; finalize_only",
                    failure
            );
            persistPoisonTerminal(sourceId, record.getValue(), message, PHASE_ORDER_CREATED, 0);
            finalizeStreamRecord(
                    sourceId, stream, recordId, identity.orderId(), identity.voucherId()
            );
            return;
        }

        if (durableAdmissions.isDurablyAccepted(
                identity.orderId(), identity.voucherId(), identity.userId(),
                identity.idempotencyKey()
        )) {
            durableAdmissions.markRepairRequired(
                    identity.orderId(), identity.voucherId(), identity.userId(),
                    identity.idempotencyKey(), failure
            );
            String message = poisonMessage(
                    deliveryCount,
                    "durable_admission_preserved; operator_repair_required",
                    failure
            );
            persistPoisonTerminal(
                    sourceId, record.getValue(), message, PHASE_REPAIR_REQUIRED, 0
            );
            finalizeStreamRecord(
                    sourceId, stream, recordId, identity.orderId(), identity.voucherId()
            );
            return;
        }

        Integer durableReservation = jdbc.queryForObject("""
                SELECT COUNT(*) FROM commerce_reservation
                WHERE order_id=? AND voucher_id=? AND user_id=?
                  AND status IN ('RESERVED','ORDER_CREATED')
                """, Integer.class, identity.orderId(), identity.voucherId(), identity.userId());
        String phase = recoveryPhase(sourceId);
        if (isStreamTerminal(phase)) {
            String message = poisonMessage(
                    deliveryCount,
                    "existing_terminal_phase=" + phase,
                    failure
            );
            persistDeadLetter(sourceId, record.getValue(), message);
            finalizeStreamRecord(
                    sourceId, stream, recordId, identity.orderId(), identity.voucherId()
            );
            return;
        }
        if (durableReservation != null && durableReservation > 0) {
            String message = poisonMessage(
                    deliveryCount,
                    "operator_repair_required; durable_reservation_exists",
                    failure
            );
            persistPoisonTerminal(sourceId, record.getValue(), message, PHASE_REPAIR_REQUIRED, 1);
            finalizeStreamRecord(
                    sourceId, stream, recordId, identity.orderId(), identity.voucherId()
            );
            return;
        }

        if (PHASE_RECEIVED.equals(phase)) {
            int intent = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, last_error=?
                    WHERE source_id=? AND phase=?
                      AND (claim_token IS NULL OR claimed_until<CURRENT_TIMESTAMP(3))
                    """, PHASE_COMPENSATION_REQUIRED, truncate(failure.getMessage()),
                    sourceId, PHASE_RECEIVED);
            if (intent != 1) {
                throw new IllegalStateException("Poison recovery is owned by another worker");
            }
            phase = PHASE_COMPENSATION_REQUIRED;
        }
        if (!PHASE_COMPENSATION_REQUIRED.equals(phase)) {
            String message = poisonMessage(
                    deliveryCount,
                    "operator_repair_required; unexpected_phase=" + phase,
                    failure
            );
            persistPoisonTerminal(
                    sourceId, record.getValue(), message, PHASE_REPAIR_REQUIRED, 1
            );
            finalizeStreamRecord(
                    sourceId, stream, recordId, identity.orderId(), identity.voucherId()
            );
            return;
        }

        // 补偿 Lua 通过 marker 幂等执行，重试不会重复加库存。
        long compensationResult = compensate(
                sourceId,
                identity.voucherId(),
                identity.userId(),
                identity.orderId(),
                identity.idempotencyKey()
        );
        boolean compensated = compensationResult == 1L;
        String terminalPhase = compensated ? PHASE_DEAD_LETTERED : PHASE_REPAIR_REQUIRED;
        String terminalAction = compensated
                ? "admission_compensated; replay_safe"
                : "operator_repair_required; compensation_result=" + compensationResult;
        String message = poisonMessage(deliveryCount, terminalAction, failure);
        persistPoisonTerminal(
                sourceId,
                record.getValue(),
                message,
                terminalPhase,
                compensated ? 0 : 1
        );
        finalizeStreamRecord(
                sourceId, stream, recordId, identity.orderId(), identity.voucherId()
        );
    }

    /** 在同一 MySQL 事务中写 Recovery 终态和 Dead Letter，避免只有一侧成功。 */
    private void persistPoisonTerminal(
            String sourceId,
            Map<Object, Object> payload,
            String message,
            String phase,
            int compensationDelta
    ) {
        transactions.executeWithoutResult(status -> {
            int terminal = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, compensation_delta=?, last_error=?,
                        terminal_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL, claim_owner=NULL, claimed_until=NULL
                    WHERE source_id=?
                    """, phase, compensationDelta, message, sourceId);
            if (terminal != 1) {
                throw new IllegalStateException("Unable to persist poison recovery terminal phase");
            }
            persistDeadLetter(sourceId, payload, message);
        });
    }

    /** 幂等写入死信；重复处理只更新错误并累加重试次数。 */
    private void persistDeadLetter(
            String sourceId,
            Map<Object, Object> payload,
            String message
    ) {
        jdbc.update("""
                INSERT INTO commerce_dead_letter(source_id,payload_json,error_message)
                VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE
                    error_message=VALUES(error_message), retry_count=retry_count+1
                """, sourceId, json(payload), message);
    }

    private String poisonMessage(
            long deliveryCount,
            String terminalAction,
            RuntimeException failure
    ) {
        return truncate(
                "stream_delivery_limit=" + deliveryCount + "; "
                        + terminalAction + "; " + failure.getMessage()
        );
    }

    /** 尝试从消息提取完整业务身份；任一关键字段不合法即返回 {@code null}。 */
    private PoisonIdentity poisonIdentity(MapRecord<String, Object, Object> record) {
        try {
            return new PoisonIdentity(
                    number(record, "orderId"),
                    number(record, "voucherId"),
                    number(record, "userId"),
                    text(record, "idempotencyKey")
            );
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * 通过 Lua 原子执行 XACK 后 XDEL，避免 ACK 成功但删除失败留下无意义历史记录。
     */
    private Long acknowledgeAndDelete(String stream, String recordId) {
        return redis.execute(
                ACK_DELETE_SCRIPT,
                List.of(stream),
                ORDER_CONSUMER_GROUP,
                recordId
        );
    }

    /**
     * 处理一条订单 Stream：建立 Recovery 行、取得数据库 claim、创建订单或补偿，最后终态化 Redis。
     *
     * @param record Redis Stream 订单记录
     */
    void process(MapRecord<String, Object, Object> record) {
        String stream = recordStream(record);
        if ("true".equals(String.valueOf(record.getValue().get("bootstrap")))) {
            try {
                acknowledgeAndDelete(stream, record.getId().getValue());
            } catch (RuntimeException unavailable) {
                log.warn("Unable to acknowledge Commerce stream bootstrap record {}", record.getId());
            }
            return;
        }
        long orderId = number(record, "orderId");
        long userId = number(record, "userId");
        long voucherId = number(record, "voucherId");
        String idempotencyKey = text(record, "idempotencyKey");
        if (!keyspace.stream(voucherId).equals(stream)) {
            throw new IllegalArgumentException(
                    "Voucher was published to the wrong Commerce logical shard"
            );
        }
        String recordId = record.getId().getValue();
        String sourceId = keyspace.recoverySourceId(stream, recordId);
        try {
            // Recovery 行先于任何业务写落库，后续重投可从明确阶段继续而不是重新猜测执行结果。
            ensureRecoveryRecord(sourceId, orderId, voucherId, userId, idempotencyKey, record.getValue());
        } catch (Exception persistenceFailure) {
            log.error("Unable to initialize Commerce recovery row for {}; leaving it pending", sourceId, persistenceFailure);
            throw new IllegalStateException("Unable to initialize Commerce recovery row", persistenceFailure);
        }
        String phase = recoveryPhase(sourceId);
        if (isStreamTerminal(phase)) {
            finalizeStreamRecord(sourceId, stream, recordId, orderId, voucherId);
            return;
        }
        if (!PHASE_RECEIVED.equals(phase) && !PHASE_COMPENSATION_REQUIRED.equals(phase)) {
            throw new IllegalStateException("Unknown Commerce recovery phase: " + phase);
        }
        String claimToken = claimRecovery(sourceId);
        if (claimToken == null) {
            // 另一 Worker 仍持有未过期 claim，本次保留 Pending 等待其完成或租约过期。
            return;
        }
        try {
            phase = recoveryPhase(sourceId);
            if (PHASE_RECEIVED.equals(phase)) {
                try {
                    orders.createOrder(sourceId, claimToken, orderId, voucherId, userId, idempotencyKey);
                } catch (DuplicateKeyException duplicate) {
                    Integer identicalOrder = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM commerce_order
                            WHERE id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                            """, Integer.class, orderId, userId, voucherId, idempotencyKey);
                    if (identicalOrder != null && identicalOrder == 1) {
                        durableAdmissions.markOrderCreated(
                                orderId, voucherId, userId, idempotencyKey
                        );
                        markExistingOrderCreated(sourceId, claimToken);
                    } else if (durableAdmissions.isDurablyAccepted(
                            orderId, voucherId, userId, idempotencyKey
                    )) {
                        throw new IllegalStateException(
                                "Durably accepted order conflicts with an existing database identity",
                                duplicate
                        );
                    } else {
                        markCompensationRequired(sourceId, claimToken, duplicate);
                    }
                } catch (Exception failure) {
                    if (durableAdmissions.isDurablyAccepted(
                            orderId, voucherId, userId, idempotencyKey
                    )) {
                        throw new IllegalStateException(
                                "Durably accepted order persistence failed; leaving the Stream pending",
                                failure
                        );
                    }
                    markCompensationRequired(sourceId, claimToken, failure);
                }
                phase = recoveryPhase(sourceId);
            }
            if (PHASE_COMPENSATION_REQUIRED.equals(phase)) {
                completeRecovery(sourceId, claimToken, orderId, voucherId, userId, idempotencyKey);
            }
            phase = recoveryPhase(sourceId);
            if (isStreamTerminal(phase)) {
                finalizeStreamRecord(sourceId, stream, recordId, orderId, voucherId);
            }
        } catch (LostRecoveryClaim lostClaim) {
            log.debug("Commerce recovery ownership changed for {}", sourceId);
        } catch (Exception recoveryFailure) {
            releaseRecoveryClaim(sourceId, claimToken);
            log.error("Unable to finish Commerce order record {}; leaving it pending", sourceId, recoveryFailure);
            throw new IllegalStateException(
                    "Unable to finish Commerce order recovery",
                    recoveryFailure
            );
        }
    }

    /** 幂等订单已存在时，仅把当前 Recovery claim 推进到 ORDER_CREATED。 */
    private void markExistingOrderCreated(String sourceId, String claimToken) {
        transactions.executeWithoutResult(status -> {
            recovery.requireOwnedPhase(sourceId, claimToken, PHASE_RECEIVED);
            int terminal = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, terminal_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL, claim_owner=NULL, claimed_until=NULL
                    WHERE source_id=? AND claim_token=? AND phase=?
                    """, PHASE_ORDER_CREATED, sourceId, claimToken, PHASE_RECEIVED);
            if (terminal != 1) throw new LostRecoveryClaim();
        });
    }

    /** 订单事务失败且没有持久 ACCEPTED 承诺时，先持久化补偿意图再调用 Redis。 */
    private void markCompensationRequired(String sourceId, String claimToken, Exception failure) {
        transactions.executeWithoutResult(status -> {
            recovery.requireOwnedPhase(sourceId, claimToken, PHASE_RECEIVED);
            int changed = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, last_error=?
                    WHERE source_id=? AND claim_token=? AND phase=?
                    """, PHASE_COMPENSATION_REQUIRED, truncate(failure.getMessage()),
                    sourceId, claimToken, PHASE_RECEIVED);
            if (changed != 1) throw new LostRecoveryClaim();
        });
    }

    /**
     * 执行已持久化的补偿意图，并把结果与 Dead Letter 写入 MySQL 终态。
     *
     * <p>若 Redis 无法证明补偿成功，则进入 REPAIR_REQUIRED 并保留 compensation_delta=1，
     * 由一致性修复统一重建库存。</p>
     */
    void completeRecovery(
            String sourceId,
            String claimToken,
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        // Redis 调用前先在数据库确认 claim/阶段。若进程在 Lua marker 写入后崩溃，
        // 同一阶段可重放补偿，且不会长时间占用数据库事务。
        recovery.requireOwnedPhase(sourceId, claimToken, PHASE_COMPENSATION_REQUIRED);
        String failure = jdbc.queryForObject(
                "SELECT last_error FROM commerce_order_recovery WHERE source_id=?",
                String.class,
                sourceId
        );
        long compensationResult = compensate(
                sourceId,
                voucherId,
                userId,
                orderId,
                idempotencyKey
        );
        boolean repairRequired = compensationResult != 1L;
        int compensationDelta = repairRequired ? 1 : 0;
        String terminalPhase = repairRequired ? PHASE_REPAIR_REQUIRED : PHASE_DEAD_LETTERED;
        String recoveryMessage = repairRequired
                ? truncate((failure == null ? "order_processing_failed" : failure)
                + "; compensation_result=" + compensationResult)
                : truncate(failure);
        transactions.executeWithoutResult(status -> {
            recovery.requireOwnedPhase(sourceId, claimToken, PHASE_COMPENSATION_REQUIRED);
            jdbc.update("""
                    INSERT INTO commerce_dead_letter(
                        source_id,payload_json,error_message
                    )
                    SELECT source_id,payload_json,?
                    FROM commerce_order_recovery
                    WHERE source_id=?
                    ON DUPLICATE KEY UPDATE
                        error_message=VALUES(error_message), retry_count=retry_count+1
                    """, recoveryMessage, sourceId);
            int terminal = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, compensation_delta=?, last_error=?,
                        terminal_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL, claim_owner=NULL, claimed_until=NULL
                    WHERE source_id=? AND claim_token=? AND phase=?
                    """, terminalPhase, compensationDelta, recoveryMessage,
                    sourceId, claimToken, PHASE_COMPENSATION_REQUIRED);
            if (terminal != 1) throw new LostRecoveryClaim();
        });
    }

    /**
     * 以 sourceId 幂等建立 Stream Recovery 行，并复核来源对应的完整订单身份。
     */
    private void ensureRecoveryRecord(
            String sourceId,
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey,
            Map<Object, Object> payload
    ) {
        jdbc.update("""
                INSERT IGNORE INTO commerce_order_recovery(
                    source_id,source_type,order_id,voucher_id,user_id,idempotency_key,
                    payload_json,phase
                ) VALUES (?,'STREAM_ORDER',?,?,?,?,?,?)
                """, sourceId, orderId, voucherId, userId, idempotencyKey,
                json(payload), PHASE_RECEIVED);
        Integer identical = jdbc.queryForObject("""
                SELECT COUNT(*) FROM commerce_order_recovery
                WHERE source_id=? AND source_type='STREAM_ORDER'
                  AND order_id=? AND voucher_id=? AND user_id=? AND idempotency_key=?
                """, Integer.class, sourceId, orderId, voucherId, userId, idempotencyKey);
        if (identical == null || identical != 1) {
            throw new IllegalStateException("Stream source identity conflicts with its recovery row");
        }
    }

    private String recoveryPhase(String sourceId) {
        return jdbc.queryForObject(
                "SELECT phase FROM commerce_order_recovery WHERE source_id=?",
                String.class,
                sourceId
        );
    }

    /**
     * 以可过期 claim 领取 RECEIVED/COMPENSATION_REQUIRED Recovery 行。
     *
     * @return 本次随机 claim token；竞争失败返回 {@code null}
     */
    String claimRecovery(String sourceId) {
        String token = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE commerce_order_recovery
                SET claim_token=?, claim_owner=?,
                    claim_version=claim_version+1,
                    claimed_until=TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(3))
                WHERE source_id=?
                  AND phase IN (?,?)
                  AND (claim_token IS NULL OR claimed_until<CURRENT_TIMESTAMP(3))
                """, token, consumerName, sourceId, PHASE_RECEIVED, PHASE_COMPENSATION_REQUIRED);
        return claimed == 1 ? token : null;
    }

    /** 失败后尽力释放当前 claim；释放失败由 claimed_until 到期后自动回收。 */
    private void releaseRecoveryClaim(String sourceId, String claimToken) {
        try {
            jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET claim_token=NULL, claim_owner=NULL, claimed_until=NULL
                    WHERE source_id=? AND claim_token=?
                    """, sourceId, claimToken);
        } catch (Exception releaseFailure) {
            log.warn("Unable to release Commerce recovery claim for {}", sourceId);
        }
    }

    private boolean isStreamTerminal(String phase) {
        return PHASE_ORDER_CREATED.equals(phase)
                || PHASE_DEAD_LETTERED.equals(phase)
                || PHASE_REPAIR_REQUIRED.equals(phase)
                || PHASE_RESTORED.equals(phase);
    }

    /**
     * 在 Recovery 已终态后，原子清理在途成员、ACK/删除 Stream 记录和补偿 marker。
     *
     * <p>Lua 可重复执行；若连接在脚本执行后断开，记录可能不再投递，否则 XCLAIM 会从数据库终态
     * 直接重试本步骤，不会再次创建订单或补偿。</p>
     */
    void finalizeStreamRecord(
            String sourceId,
            String stream,
            String recordId,
            long orderId,
            long voucherId
    ) {
        try {
            Long result = redis.execute(
                    FINALIZE_ORDER_SCRIPT,
                    List.of(
                            keyspace.inflight(voucherId),
                            stream,
                            keyspace.compensationMarker(voucherId, sourceId)
                    ),
                    inflightMember(recordId, orderId),
                    recordId,
                    ORDER_CONSUMER_GROUP
            );
            if (result == null) {
                throw new IllegalStateException("Redis did not return a finalization result");
            }
            jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET finalized_at=COALESCE(finalized_at,CURRENT_TIMESTAMP(3))
                    WHERE source_id=? AND phase IN (?,?,?,?)
                    """, sourceId, PHASE_ORDER_CREATED, PHASE_DEAD_LETTERED,
                    PHASE_REPAIR_REQUIRED, PHASE_RESTORED);
        } catch (Exception failure) {
            // 数据库阶段已终态，失败只影响 Redis 收尾；保留可恢复状态，不回滚或重复业务事务。
            log.warn("Unable to finalize Commerce order record {}; it remains recoverable", sourceId);
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void expireDueOrders() {
        if (streamEnabled) orders.expireDueOrders();
    }

    /** 重试取消/退款后仍处于 RESTORE_PENDING 的 Redis 库存恢复，不重复增加 MySQL 库存。 */
    @Scheduled(
            fixedDelayString = "${citycopilot.commerce.inventory-recovery-ms:5000}",
            initialDelayString = "${citycopilot.commerce.inventory-recovery-ms:5000}"
    )
    public void resumePendingInventoryRestores() {
        if (!streamEnabled) return;
        List<Map<String, Object>> pending;
        try {
            pending = jdbc.queryForList("""
                    SELECT r.source_id,r.order_id,r.voucher_id,r.user_id,r.inventory_fence_token,o.status
                    FROM commerce_order_recovery r JOIN commerce_order o ON o.id=r.order_id
                    WHERE r.source_type='STATE_TRANSITION' AND r.phase=?
                    ORDER BY r.created_at
                    LIMIT 100
                    """, PHASE_RESTORE_PENDING);
        } catch (Exception unavailable) {
            log.warn("Unable to query pending Commerce inventory restores", unavailable);
            return;
        }
        for (Map<String, Object> row : pending) {
            try {
                transactions.executeWithoutResult(tx -> {
                    var claimed = jdbc.queryForList("""
                            SELECT source_id FROM commerce_order_recovery
                            WHERE source_id=? AND phase=? FOR UPDATE SKIP LOCKED
                            """, String.class, row.get("source_id"), PHASE_RESTORE_PENDING);
                    if (claimed.isEmpty()) return;
                    orders.attemptTransitionInventoryRestore(
                        String.valueOf(row.get("source_id")),
                        ((Number) row.get("order_id")).longValue(),
                        ((Number) row.get("voucher_id")).longValue(),
                        ((Number) row.get("user_id")).longValue(),
                        ((Number) row.get("status")).intValue() == 4,
                        String.valueOf(row.get("inventory_fence_token"))
                    );
                });
            } catch (Exception failure) {
                log.warn("Unable to resume inventory restore for {}", row.get("source_id"));
            }
        }
    }

    /**
     * 幂等撤销一次未落单准入；若用户还有其他有效购买事实，则保留 buyers 集合成员。
     *
     * @return Lua 补偿状态码；1 表示已补偿或幂等确认成功
     */
    private long compensate(
            String sourceId,
            long voucherId,
            long userId,
            long orderId,
            String idempotencyKey
    ) {
        Integer existingPurchase = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT id AS order_id FROM commerce_order
                    WHERE user_id=? AND voucher_id=? AND id<>? AND status<>4
                    UNION
                    SELECT order_id FROM commerce_reservation
                    WHERE user_id=? AND voucher_id=? AND order_id<>?
                      AND status IN ('RESERVED','ORDER_CREATED')
                ) existing_purchase
                """, Integer.class, userId, voucherId, orderId, userId, voucherId, orderId);
        boolean preserveBuyer = existingPurchase != null && existingPurchase > 0;
        Long result = redis.execute(
                COMPENSATE_SCRIPT,
                List.of(
                        keyspace.stock(voucherId),
                        keyspace.buyers(voucherId),
                        keyspace.idempotency(voucherId, userId, idempotencyKey),
                        keyspace.compensationMarker(voucherId, sourceId)
                ),
                String.valueOf(userId),
                String.valueOf(orderId),
                String.valueOf(preserveBuyer),
                String.valueOf(voucherId)
        );
        if (result == null) {
            throw new IllegalStateException("Unable to atomically compensate Commerce admission");
        }
        return result;
    }

    /** 将 Stream 记录来源与订单 ID 组合为在途集合成员，供终态 Lua 精确删除。 */
    static String inflightMember(String sourceId, long orderId) {
        return sourceId + "|" + orderId;
    }

    private long number(MapRecord<String, Object, Object> record, String key) {
        return Long.parseLong(text(record, key));
    }

    private String text(MapRecord<String, Object, Object> record, String key) {
        Object value = record.getValue().get(key);
        if (value == null) throw new IllegalArgumentException("Stream field missing: " + key);
        return String.valueOf(value);
    }

    private String recordStream(MapRecord<String, Object, Object> record) {
        String stream = record.getStream();
        if (stream != null) return stream;
        if (keyspace.streams().size() == 1) return keyspace.streams().get(0);
        throw new IllegalArgumentException("Stream record is missing its logical shard key");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize commerce payload", error);
        }
    }

    private String truncate(String value) {
        String safe = value == null ? "unknown_error" : value;
        return safe.substring(0, Math.min(1024, safe.length()));
    }

    /** 递归识别 Redis 消费组已存在的 BUSYGROUP 异常，使启动初始化保持幂等。 */
    static boolean isBusyGroup(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toUpperCase(Locale.ROOT).contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record PoisonIdentity(
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {}


}
