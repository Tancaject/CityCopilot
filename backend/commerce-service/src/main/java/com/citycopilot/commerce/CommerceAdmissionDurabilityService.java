package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceAdmissionOutcomePolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同步秒杀准入决策的 MySQL 持久化事实来源。
 *
 * <p>全局 Redis 幂等键与 Voucher 本地 Lua/Stream 位于不同 Cluster slot，脱离物理连接的 WAIT
 * 无法证明两次写分别到达各自主节点副本。本账本因此在 Redis 准入前固定订单身份，只有当
 * Voucher 本地写已证明成功后才记为 {@link Status#ACCEPTED}；该状态提交前 API 不得返回受理。</p>
 *
 * <p>后台恢复故意不扫描 {@link Status#PREPARED}：客户端可用同一幂等键和预留订单 ID 完成它，
 * Worker 只能恢复已经由请求链路证明接受的行，避免后台把“结果未知”擅自变成已接受。</p>
 */
@Component
final class CommerceAdmissionDurabilityService {
    private static final Logger log = LoggerFactory.getLogger(CommerceAdmissionDurabilityService.class);
    /** 同步准入账本决策的指标名。 */
    private static final String METRIC_DECISION = "citycopilot.commerce.admission.durability";
    /** ACCEPTED 后台恢复结果的指标名。 */
    private static final String METRIC_RECOVERY = "citycopilot.commerce.admission.recovery";
    /** 为旧订单补录准入身份时使用的固定路由代际。 */
    private static final String LEGACY_ORDER_ROUTING_GENERATION = "legacy-order-v0";

    /** 持久化幂等身份、状态转换和恢复 claim。 */
    private final JdbcTemplate jdbc;
    private final CommerceReadCache readCache;
    /** 新身份首次准备时分配订单 ID。 */
    private final CommerceOrderIdGenerator orderIds;
    /** PREPARED 插入与 ACCEPTED 转换的组提交写入器。 */
    private final CommerceAdmissionLedgerWriter ledger;
    /** 当前实例拥有的路由代际指纹。 */
    private final String routingGeneration;
    /** 当前实例拥有的物理分片数指纹。 */
    private final int routingShardCount;
    /** 记录准入决策、恢复结果和耗时。 */
    private final MeterRegistry metrics;
    /** 单轮后台恢复最多 claim 的 ACCEPTED 行数。 */
    private final int recoveryBatchSize;
    /** ACCEPTED 至少等待多久才交给恢复 Worker，给正常 Stream 消费留出时间。 */
    private final long recoveryMinAgeMicros;
    /** 单次恢复 claim 的有效期。 */
    private final long recoveryLeaseMicros;
    /** Redis 队列已确认后，下一次数据库复查前的延迟。 */
    private final long recoveryRecheckMicros;
    /** 自动恢复失败多少次后进入人工 REPAIR_REQUIRED。 */
    private final int recoveryMaxAttempts;
    /** 允许继续接受新请求的 ACCEPTED 积压数量上限。 */
    private final long maxAcceptedBacklog;
    /** 允许继续接受新请求的最老 ACCEPTED 行年龄上限。 */
    private final long maxAcceptedAgeMillis;
    /** 容量统计缓存周期，避免每个请求都执行聚合 SQL。 */
    private final long capacityCacheNanos;
    /** 当前实例恢复 claim 的可观测所有者名。 */
    private final String claimOwner;
    /** 最近采样的 ACCEPTED 积压数，既供 Gauge 也供准入背压判断。 */
    private final AtomicLong acceptedBacklog = new AtomicLong();
    /** 最近采样的最老 ACCEPTED 行年龄。 */
    private final AtomicLong oldestAcceptedAgeMillis = new AtomicLong();
    /** 最近采样的人工修复积压数。 */
    private final AtomicLong repairRequired = new AtomicLong();
    /** 最近一次容量采样的单调时钟时间，用于控制缓存有效期。 */
    private volatile long capacityObservedAtNanos;

    @Autowired
    CommerceAdmissionDurabilityService(
            JdbcTemplate jdbc,
            CommerceOrderIdGenerator orderIds,
            CommerceRedisKeyspace keyspace,
            MeterRegistry metrics,
            @Value("${citycopilot.commerce.admission-recovery-batch-size:100}") int recoveryBatchSize,
            @Value("${citycopilot.commerce.admission-recovery-min-age-ms:5000}") long recoveryMinAgeMillis,
            @Value("${citycopilot.commerce.admission-recovery-lease-ms:30000}") long recoveryLeaseMillis,
            @Value("${citycopilot.commerce.admission-recovery-recheck-ms:5000}") long recoveryRecheckMillis,
            @Value("${citycopilot.commerce.admission-recovery-max-attempts:20}") int recoveryMaxAttempts,
            @Value("${citycopilot.commerce.admission-max-backlog:50000}") long maxAcceptedBacklog,
            @Value("${citycopilot.commerce.admission-max-oldest-age-ms:300000}") long maxAcceptedAgeMillis,
            @Value("${citycopilot.commerce.admission-capacity-cache-ms:1000}") long capacityCacheMillis,
            @Value("${citycopilot.commerce.consumer-name:commerce-local}") String nodeName,
            CommerceReadCache readCache,
            CommerceAdmissionLedgerWriter ledger
    ) {
        if (recoveryBatchSize < 1 || recoveryBatchSize > 10_000) {
            throw new IllegalArgumentException("Admission recovery batch size must be 1..10000");
        }
        if (recoveryMinAgeMillis < 0 || recoveryLeaseMillis < 1_000 || recoveryRecheckMillis < 100) {
            throw new IllegalArgumentException("Admission recovery age/lease configuration is invalid");
        }
        if (recoveryMaxAttempts < 1 || maxAcceptedBacklog < 1
                || maxAcceptedAgeMillis < 1 || capacityCacheMillis < 1) {
            throw new IllegalArgumentException("Admission durability thresholds must be positive");
        }
        this.jdbc = jdbc;
        this.readCache = readCache;
        this.orderIds = orderIds;
        this.ledger = ledger;
        this.routingGeneration = keyspace.routingGeneration();
        this.routingShardCount = keyspace.physicalShardCount();
        this.metrics = metrics;
        this.recoveryBatchSize = recoveryBatchSize;
        this.recoveryMinAgeMicros = TimeUnit.MILLISECONDS.toMicros(recoveryMinAgeMillis);
        this.recoveryLeaseMicros = TimeUnit.MILLISECONDS.toMicros(recoveryLeaseMillis);
        this.recoveryRecheckMicros = TimeUnit.MILLISECONDS.toMicros(recoveryRecheckMillis);
        this.recoveryMaxAttempts = recoveryMaxAttempts;
        this.maxAcceptedBacklog = maxAcceptedBacklog;
        this.maxAcceptedAgeMillis = maxAcceptedAgeMillis;
        this.capacityCacheNanos = TimeUnit.MILLISECONDS.toNanos(capacityCacheMillis);
        this.claimOwner = nodeName + "-admission-" + UUID.randomUUID().toString().substring(0, 8);
        Gauge.builder("citycopilot.commerce.admission.backlog", acceptedBacklog, AtomicLong::get)
                .description("Durably accepted admissions not yet represented by an order")
                .register(metrics);
        Gauge.builder("citycopilot.commerce.admission.oldest.age", oldestAcceptedAgeMillis, AtomicLong::get)
                .baseUnit("milliseconds")
                .description("Age of the oldest durably accepted admission")
                .register(metrics);
        Gauge.builder("citycopilot.commerce.admission.repair.required", repairRequired, AtomicLong::get)
                .description("Admissions requiring operator repair")
                .register(metrics);
    }

    /**
     * 固定用户幂等键对应的订单身份，或重放已有持久化决策。
     *
     * @param voucherId Voucher ID
     * @param userId 用户 ID
     * @param idempotencyKey 用户范围内的幂等键
     * @param requestFingerprint 请求业务语义指纹
     * @param requiredOrderId 死信重放要求复用的订单 ID；普通请求为空
     * @return PREPARED 或已接受的持久化准入
     */
    Admission prepare(
            long voucherId,
            long userId,
            String idempotencyKey,
            String requestFingerprint,
            Long requiredOrderId
    ) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "failure";
        try {
            Admission existing = find(userId, idempotencyKey);
            if (existing != null) {
                Admission evaluated = evaluateExisting(
                        existing, voucherId, userId, idempotencyKey,
                        requestFingerprint, requiredOrderId
                );
                outcome = evaluated.wasAccepted() ? "accepted_replay" : "prepared_replay";
                return evaluated;
            }
            // 售罄时尚未预扣任何资源，无需持久化决策：不写账本、不消耗订单号，避免售罄洪峰压到 MySQL。
            // 已有同键身份已在上方返回，因此快速拒绝不会覆盖已接受的请求。
            if (requiredOrderId == null && readCache.isSoldOut(voucherId)) {
                outcome = "sold_out";
                throw new CommerceApiException("SECKILL_STOCK_NOT_ENOUGH", "Voucher stock exhausted", 409);
            }
            // 历史订单已由 V3 迁移回填为 ORDER_CREATED 账本，同步热路径不再查询订单事实表。
            assertRecoveryCapacity();
            long orderId = requiredOrderId == null ? orderIds.nextId() : requiredOrderId;
            // INSERT IGNORE 只解决并发创建；随后必须重读并逐字段验证究竟是哪一个身份获胜。
            ledger.insertPrepared(new CommerceAdmissionLedgerWriter.Identity(orderId, userId, idempotencyKey,
                    voucherId, requestFingerprint, routingGeneration, routingShardCount));
            Admission inserted = find(userId, idempotencyKey);
            if (inserted == null) {
                throw durabilityUnavailable("Durable admission identity was not stored", null);
            }
            Admission evaluated = evaluateExisting(
                    inserted, voucherId, userId, idempotencyKey,
                    requestFingerprint, requiredOrderId
            );
            outcome = "prepared";
            return evaluated;
        } catch (CommerceApiException known) {
            if (!"failure".equals(outcome) && !"sold_out".equals(outcome)) {
                outcome = "rejected_replay";
            }
            throw known;
        } catch (RuntimeException failure) {
            log.error(
                    "Unable to prepare durable Commerce admission for user {} voucher {}",
                    userId,
                    voucherId,
                    failure
            );
            throw durabilityUnavailable("Durable Commerce admission is unavailable", failure);
        } finally {
            count(METRIC_DECISION, outcome);
            sample.stop(Timer.builder("citycopilot.commerce.admission.durability.duration")
                    .tag("operation", "prepare")
                    .register(metrics));
        }
    }

    /**
     * 在 Voucher 本地 Redis Lua 已确认复制后，将 PREPARED 转为 ACCEPTED。
     *
     * @param admission 请求开始时取得的完整持久化身份
     */
    void markAccepted(Admission admission) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            boolean changed = ledger.markAccepted(new CommerceAdmissionLedgerWriter.Identity(
                    admission.orderId(), admission.userId(), admission.idempotencyKey(), admission.voucherId(),
                    admission.requestFingerprint(), admission.routingGeneration(), admission.routingShardCount()));
            if (!changed) {
                Admission current = find(admission.userId(), admission.idempotencyKey());
                if (current == null
                        || current.status() != Status.ACCEPTED
                        && current.status() != Status.ORDER_CREATED
                        || !current.sameIdentity(admission)) {
                    throw durabilityUnavailable("Unable to confirm durable Commerce admission", null);
                }
            } else {
                acceptedBacklog.incrementAndGet();
            }
            count(METRIC_DECISION, "accepted");
        } catch (CommerceApiException known) {
            count(METRIC_DECISION, "accept_failure");
            throw known;
        } catch (RuntimeException failure) {
            count(METRIC_DECISION, "accept_failure");
            log.error("Unable to durably confirm accepted Commerce order {}", admission.orderId(), failure);
            throw durabilityUnavailable("Unable to durably confirm accepted Commerce order", failure);
        } finally {
            sample.stop(Timer.builder("citycopilot.commerce.admission.durability.duration")
                    .tag("operation", "accept")
                    .register(metrics));
        }
    }

    /**
     * 持久化可稳定重放的业务拒绝，使后续同幂等键得到相同错误。
     */
    void markRejected(Admission admission, CommerceApiException rejection) {
        try {
            int changed = jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET status='REJECTED',rejection_code=?,rejection_http_status=?,
                        rejection_message=?,terminal_at=CURRENT_TIMESTAMP(3),last_error=NULL,
                        claim_token=NULL,claim_owner=NULL,claimed_until=NULL
                    WHERE order_id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                      AND request_fingerprint=?
                      AND routing_generation=? AND physical_shard_count=?
                      AND status='PREPARED'
                    """, rejection.code(), rejection.status(), truncate(rejection.getMessage(), 255),
                    admission.orderId(), admission.userId(), admission.voucherId(),
                    admission.idempotencyKey(), admission.requestFingerprint(),
                    admission.routingGeneration(), admission.routingShardCount());
            if (changed != 1) {
                Admission current = find(admission.userId(), admission.idempotencyKey());
                if (current == null || current.status() != Status.REJECTED
                        || !current.sameIdentity(admission)
                        || !rejection.code().equals(current.rejectionCode())
                        || rejection.status() != current.rejectionHttpStatus()) {
                    throw durabilityUnavailable("Unable to persist Commerce admission rejection", null);
                }
            }
            count(METRIC_DECISION, "rejected");
        } catch (CommerceApiException known) {
            count(METRIC_DECISION, "reject_persist_failure");
            throw known;
        } catch (RuntimeException failure) {
            count(METRIC_DECISION, "reject_persist_failure");
            log.error("Unable to persist rejection for Commerce order {}", admission.orderId(), failure);
            throw durabilityUnavailable("Unable to persist Commerce admission rejection", failure);
        }
    }

    /**
     * 通过带过期时间的数据库 claim 领取一批长期未生成订单的 ACCEPTED 准入。
     *
     * @return 当前 Worker 独占的恢复任务；数据库不可用时记录错误并返回空列表
     */
    List<Admission> claimAcceptedBatch() {
        String token = UUID.randomUUID().toString();
        try {
            // 先用不加锁的一致性读挑候选，再按主键认领。直接按恢复索引 UPDATE 会先锁二级索引再锁主键，
            // 与按主键释放 claim 的语句加锁顺序相反，两个实例并发时会死锁。
            List<Long> candidates = jdbc.queryForList("""
                    SELECT order_id FROM commerce_admission_ledger
                    WHERE status='ACCEPTED'
                      AND routing_generation=? AND physical_shard_count=?
                      AND accepted_at<=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3))
                      AND (claimed_until IS NULL OR claimed_until<CURRENT_TIMESTAMP(3))
                    ORDER BY accepted_at,order_id
                    LIMIT ?
                    """, Long.class, routingGeneration, routingShardCount, -recoveryMinAgeMicros, recoveryBatchSize);
            if (candidates.isEmpty()) return List.of();
            List<Object> arguments = new ArrayList<>(List.of(token, claimOwner, recoveryLeaseMicros));
            arguments.addAll(candidates);
            // 候选可能已被其他实例认领或已落单，UPDATE 内重新校验状态与租约，只认领仍可领的行。
            int claimed = jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET claim_token=?,claim_owner=?,
                        claimed_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3))
                    WHERE order_id IN (%s)
                      AND status='ACCEPTED'
                      AND (claimed_until IS NULL OR claimed_until<CURRENT_TIMESTAMP(3))
                    """.formatted(String.join(",", Collections.nCopies(candidates.size(), "?"))),
                    arguments.toArray());
            if (claimed == 0) return List.of();
            return rows(jdbc.queryForList("""
                    SELECT order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
                           status,rejection_code,rejection_http_status,rejection_message,
                           attempts,claim_token,routing_generation,physical_shard_count
                    FROM commerce_admission_ledger
                    WHERE status='ACCEPTED' AND claim_token=?
                    ORDER BY accepted_at,order_id
                    """, token));
        } catch (RuntimeException failure) {
            count(METRIC_RECOVERY, "claim_failure");
            log.error("Unable to claim durable Commerce admissions", failure);
            return List.of();
        }
    }

    /** 将当前 Worker claim 的 ACCEPTED 行终态化为 ORDER_CREATED。 */
    void markOrderCreated(Admission admission) {
        if (admission.status() != Status.ACCEPTED
                || admission.claimToken() == null
                || admission.claimToken().isBlank()) {
            throw new IllegalArgumentException(
                    "Order creation recovery requires a claimed ACCEPTED admission"
            );
        }
        int changed = jdbc.update("""
                UPDATE commerce_admission_ledger
                SET status='ORDER_CREATED',terminal_at=COALESCE(terminal_at,CURRENT_TIMESTAMP(3)),
                    claim_token=NULL,claim_owner=NULL,claimed_until=NULL,last_error=NULL
                WHERE order_id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                  AND request_fingerprint=? AND status='ACCEPTED' AND claim_token=?
                """, admission.orderId(), admission.userId(), admission.voucherId(),
                admission.idempotencyKey(), admission.requestFingerprint(), admission.claimToken());
        if (changed == 0) {
            Admission current = find(admission.userId(), admission.idempotencyKey());
            if (current == null || current.status() != Status.ORDER_CREATED
                    || !current.sameIdentity(admission)) {
                throw new IllegalStateException("Durable admission identity changed before order creation");
            }
        } else if (changed != 1) {
            throw new IllegalStateException("Unexpected durable admission terminalization count");
        } else {
            acceptedBacklog.updateAndGet(value -> Math.max(0L, value - 1L));
        }
        count(METRIC_RECOVERY, "order_created");
    }

    /**
     * 订单事务使用：按订单身份终态化账本；兼容先有订单、后补账本的历史数据。
     */
    void markOrderCreated(
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        String fingerprint = requestFingerprint(voucherId);
        Admission current = find(userId, idempotencyKey);
        if (current == null) {
            Admission materialized = materializeLegacyOrderIfPresent(
                    voucherId,
                    userId,
                    idempotencyKey,
                    fingerprint,
                    orderId
            );
            if (materialized == null) {
                throw new IllegalStateException(
                        "Created order is missing from both MySQL and its durable admission ledger"
                );
            }
            count(METRIC_RECOVERY, "legacy_order_materialized");
            return;
        }
        if (!current.sameOrderIdentity(
                orderId,
                voucherId,
                userId,
                idempotencyKey,
                fingerprint
        )) {
            throw new IllegalStateException("Created order conflicts with its durable admission identity");
        }
        if (current.status() == Status.ORDER_CREATED) return;
        if (current.status() == Status.REJECTED || current.status() == Status.REPAIR_REQUIRED) {
            Admission terminal = materializeLegacyOrderIfPresent(
                    voucherId,
                    userId,
                    idempotencyKey,
                    fingerprint,
                    orderId
            );
            if (terminal == null || terminal.status() != Status.ORDER_CREATED) {
                throw new IllegalStateException("Unable to materialize existing Commerce order");
            }
            return;
        }

        Status previousStatus = current.status();
        int changed = jdbc.update("""
                UPDATE commerce_admission_ledger
                SET status='ORDER_CREATED',terminal_at=COALESCE(terminal_at,CURRENT_TIMESTAMP(3)),
                    claim_token=NULL,claim_owner=NULL,claimed_until=NULL,last_error=NULL
                WHERE order_id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                  AND request_fingerprint=? AND status=?
                """, orderId, userId, voucherId, idempotencyKey, fingerprint,
                previousStatus.name());
        if (changed == 1) {
            if (previousStatus == Status.ACCEPTED) {
                acceptedBacklog.updateAndGet(value -> Math.max(0L, value - 1L));
            }
            return;
        }
        Admission terminal = find(userId, idempotencyKey);
        if (terminal == null || terminal.status() != Status.ORDER_CREATED
                || !terminal.sameOrderIdentity(
                orderId,
                voucherId,
                userId,
                idempotencyKey,
                fingerprint
        )) {
            throw new IllegalStateException("Created order conflicts with its durable admission identity");
        }
    }

    /**
     * 在批量建单事务内按主键顺序锁定账本行，挑出可直接终态化的订单。
     *
     * <p>只接受 PREPARED/ACCEPTED 且身份逐项一致的行：Lua 写入 Stream 早于 markAccepted，
     * Worker 读到 PREPARED 是正常竞争。缺账本、已拒绝、待修复或已建单的订单由逐条路径处理。</p>
     *
     * @return orderId 到加锁时账本状态的映射，只含可批量处理的订单
     */
    Map<Long, Status> lockBatchCandidates(long voucherId, List<CommerceOrderService.BatchOrder> orders) {
        Map<Long, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT order_id,user_id,voucher_id,idempotency_key,request_fingerprint,status
                FROM commerce_admission_ledger
                WHERE order_id IN (%s)
                ORDER BY order_id
                FOR UPDATE
                """.formatted(CommerceOrderService.placeholders(orders.size(), 1)),
                orders.stream().map(order -> (Object) order.orderId()).toArray())) {
            rows.put(number(row, "order_id"), row);
        }
        String fingerprint = requestFingerprint(voucherId);
        Map<Long, Status> eligible = new HashMap<>();
        for (CommerceOrderService.BatchOrder order : orders) {
            Map<String, Object> row = rows.get(order.orderId());
            if (row == null) continue;
            Status status = status(String.valueOf(value(row, "status")));
            if ((status == Status.PREPARED || status == Status.ACCEPTED)
                    && number(row, "user_id") == order.userId()
                    && number(row, "voucher_id") == voucherId
                    && order.idempotencyKey().equals(String.valueOf(value(row, "idempotency_key")))
                    && fingerprint.equals(String.valueOf(value(row, "request_fingerprint")))) {
                eligible.put(order.orderId(), status);
            }
        }
        return eligible;
    }

    /**
     * 在批量建单事务内把已锁定的账本行逐单配对终态化为 ORDER_CREATED。
     *
     * <p>每单按 (订单, 用户, 券, 幂等键, 指纹, 加锁时状态) 元组匹配，影响行数不等于批量大小时
     * 抛出 {@link CommerceOrderService.BatchRejected} 让整批回滚。积压计数不在这里扣减，
     * 由调用方在事务提交后调用 {@link #releaseAcceptedBacklog(int)}，避免回滚后少算。</p>
     *
     * @return 其中由 ACCEPTED 转出的数量
     */
    int markOrdersCreated(long voucherId, List<CommerceOrderService.BatchOrder> orders, Map<Long, Status> locked) {
        String fingerprint = requestFingerprint(voucherId);
        List<Object> arguments = new ArrayList<>();
        int accepted = 0;
        for (CommerceOrderService.BatchOrder order : orders) {
            Status previous = locked.get(order.orderId());
            if (previous == Status.ACCEPTED) accepted++;
            arguments.addAll(List.of(order.orderId(), order.userId(), voucherId, order.idempotencyKey(),
                    fingerprint, previous.name()));
        }
        int changed = jdbc.update("""
                UPDATE commerce_admission_ledger
                SET status='ORDER_CREATED',terminal_at=COALESCE(terminal_at,CURRENT_TIMESTAMP(3)),
                    claim_token=NULL,claim_owner=NULL,claimed_until=NULL,last_error=NULL
                WHERE (order_id,user_id,voucher_id,idempotency_key,request_fingerprint,status) IN (%s)
                """.formatted(CommerceOrderService.placeholders(orders.size(), 6)), arguments.toArray());
        if (changed != orders.size()) {
            throw new CommerceOrderService.BatchRejected("admission ledger changed " + changed + " of " + orders.size());
        }
        return accepted;
    }

    /** 批量建单事务提交后，按实际由 ACCEPTED 转出的数量扣减本地积压计数。 */
    void releaseAcceptedBacklog(int transitions) {
        if (transitions > 0) acceptedBacklog.updateAndGet(value -> Math.max(0L, value - transitions));
    }

    /** @return 给定订单身份是否已被请求链路持久证明为 ACCEPTED/ORDER_CREATED */
    boolean isDurablyAccepted(
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        Admission current = find(userId, idempotencyKey);
        return current != null
                && current.orderId() == orderId
                && current.voucherId() == voucherId
                && (current.status() == Status.ACCEPTED
                || current.status() == Status.ORDER_CREATED);
    }

    /** 将无法自动兑现的持久化准入转为人工修复态，并停止继续自动重试。 */
    void markRepairRequired(
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey,
            RuntimeException failure
    ) {
        int changed = jdbc.update("""
                UPDATE commerce_admission_ledger
                SET status='REPAIR_REQUIRED',last_error=?,terminal_at=CURRENT_TIMESTAMP(3),
                    claim_token=NULL,claim_owner=NULL,claimed_until=NULL
                WHERE order_id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                  AND status='ACCEPTED'
                """, truncate(failure.getMessage(), 1024), orderId, userId, voucherId,
                idempotencyKey);
        if (changed == 1) {
            repairRequired.incrementAndGet();
            acceptedBacklog.updateAndGet(value -> Math.max(0L, value - 1L));
            count(METRIC_RECOVERY, "repair_required");
            log.error("Durably accepted Commerce order {} requires operator repair", orderId, failure);
        }
    }

    /**
     * 释放失败的恢复 claim；达到最大尝试次数时改为 REPAIR_REQUIRED，否则留待下一轮。
     */
    void releaseForRetryOrRepair(Admission admission, RuntimeException failure) {
        String message = truncate(failure.getMessage(), 1024);
        int nextAttempts = admission.attempts() + 1;
        if (nextAttempts >= recoveryMaxAttempts) {
            int terminal = jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET status='REPAIR_REQUIRED',attempts=attempts+1,
                        last_error=?,terminal_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL,claim_owner=NULL,claimed_until=NULL
                    WHERE order_id=? AND status='ACCEPTED' AND claim_token=?
                    """, message, admission.orderId(), admission.claimToken());
            if (terminal == 1) {
                repairRequired.incrementAndGet();
                acceptedBacklog.updateAndGet(value -> Math.max(0L, value - 1L));
                count(METRIC_RECOVERY, "repair_required");
                log.error(
                        "Commerce admission {} requires operator repair after {} attempts",
                        admission.orderId(),
                        nextAttempts,
                        failure
                );
            }
            return;
        }
        int released = jdbc.update("""
                UPDATE commerce_admission_ledger
                SET attempts=attempts+1,claim_token=NULL,claim_owner=NULL,
                    claimed_until=NULL,last_error=?
                WHERE order_id=? AND status='ACCEPTED' AND claim_token=?
                """, message, admission.orderId(), admission.claimToken());
        if (released == 1) count(METRIC_RECOVERY, "retry_scheduled");
    }

    /**
     * Redis 恢复 Lua 已确认入队后释放 claim，并设置短暂复查延迟等待正常 Stream Worker 落单。
     */
    void releaseAfterQueueConfirmation(Admission admission) {
        int released = jdbc.update("""
                UPDATE commerce_admission_ledger
                SET claim_token=NULL,claim_owner=NULL,
                    claimed_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3)),
                    last_error=NULL
                WHERE order_id=? AND status='ACCEPTED' AND claim_token=?
                """, recoveryRecheckMicros, admission.orderId(), admission.claimToken());
        if (released != 1) {
            Admission current = find(admission.userId(), admission.idempotencyKey());
            if (current == null || current.status() != Status.ORDER_CREATED
                    || !current.sameIdentity(admission)) {
                throw new IllegalStateException("Lost durable admission recovery claim");
            }
        }
        count(METRIC_RECOVERY, "queue_confirmed");
    }

    /**
     * 显式人工恢复转换：绝不接受 PREPARED，只把曾经 ACCEPTED 且已告警终态的身份重新交给
     * 同一套带 claim 的 MySQL 恢复路径。
     */
    void requeueRepairRequired(long orderId) {
        int changed = jdbc.update("""
                UPDATE commerce_admission_ledger
                SET status='ACCEPTED',attempts=0,accepted_at=CURRENT_TIMESTAMP(3),
                    terminal_at=NULL,last_error=NULL,
                    claim_token=NULL,claim_owner=NULL,claimed_until=NULL
                WHERE order_id=? AND status='REPAIR_REQUIRED'
                """, orderId);
        if (changed != 1) {
            throw new CommerceApiException(
                    "COMMERCE_ADMISSION_REPAIR_NOT_FOUND",
                    "Repair-required Commerce admission was not found",
                    404
            );
        }
        repairRequired.updateAndGet(value -> Math.max(0L, value - 1L));
        acceptedBacklog.incrementAndGet();
        count(METRIC_RECOVERY, "repair_requeued");
        log.warn("Operator requeued repair-required Commerce admission {}", orderId);
    }

    /** 定期刷新积压 Gauge 和准入背压依据；失败不覆盖上次可观测值。 */
    @Scheduled(
            fixedDelayString = "${citycopilot.commerce.admission-metrics-refresh-ms:5000}",
            initialDelayString = "${citycopilot.commerce.admission-metrics-refresh-ms:5000}"
    )
    void refreshCapacityMetrics() {
        try {
            refreshCapacity();
        } catch (RuntimeException failure) {
            count(METRIC_DECISION, "capacity_refresh_failure");
            log.warn("Unable to refresh Commerce admission durability metrics", failure);
        }
    }

    /** 验证幂等身份完全一致，并把已有状态映射为重放结果或稳定异常。 */
    private Admission evaluateExisting(
            Admission admission,
            long voucherId,
            long userId,
            String idempotencyKey,
            String requestFingerprint,
            Long requiredOrderId
    ) {
        if (admission.userId() != userId
                || admission.voucherId() != voucherId
                || !admission.idempotencyKey().equals(idempotencyKey)
                || !admission.requestFingerprint().equals(requestFingerprint)
                || requiredOrderId != null && admission.orderId() != requiredOrderId) {
            throw new CommerceApiException(
                    "COMMERCE_IDEMPOTENCY_CONFLICT",
                    "Idempotency key is already in use for another Commerce admission",
                    409
            );
        }
        Admission current = admission;
        return switch (current.status()) {
            case PREPARED -> {
                if (!current.matchesRouting(routingGeneration, routingShardCount)) {
                    throw new CommerceApiException(
                            "COMMERCE_ADMISSION_ROUTING_CONFLICT",
                            "Prepared Commerce admission belongs to another Redis keyspace configuration",
                            503
                    );
                }
                assertRecoveryCapacity();
                yield current;
            }
            case ACCEPTED, ORDER_CREATED -> current;
            case REJECTED -> throw replayRejection(current);
            case REPAIR_REQUIRED -> throw new CommerceApiException(
                    "COMMERCE_ADMISSION_REPAIR_REQUIRED",
                    "Accepted Commerce order requires operator repair",
                    503
            );
        };
    }

    /** 按订单号顺序列出某券最多 limit 条仍为 PREPARED 的准入身份，供活动结束后的对账判断它们是否从未进入 Redis。 */
    List<Admission> preparedFor(long voucherId, int limit) {
        return rows(jdbc.queryForList("""
                SELECT order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
                       status,rejection_code,rejection_http_status,rejection_message,
                       attempts,claim_token,routing_generation,physical_shard_count
                FROM commerce_admission_ledger
                WHERE voucher_id=? AND status='PREPARED'
                ORDER BY order_id
                LIMIT ?
                """, voucherId, limit));
    }

    private Admission find(long userId, String idempotencyKey) {
        List<Admission> admissions = rows(jdbc.queryForList("""
                SELECT order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
                       status,rejection_code,rejection_http_status,rejection_message,
                       attempts,claim_token,routing_generation,physical_shard_count
                FROM commerce_admission_ledger
                WHERE user_id=? AND idempotency_key=?
                """, userId, idempotencyKey));
        if (admissions.size() > 1) {
            throw new IllegalStateException("Durable admission uniqueness constraint is violated");
        }
        return admissions.isEmpty() ? null : admissions.get(0);
    }

    /**
     * 若历史订单已存在但没有准入账本，则按精确订单身份补建 ORDER_CREATED 终态行。
     */
    private Admission materializeLegacyOrderIfPresent(
            long voucherId,
            long userId,
            String idempotencyKey,
            String requestFingerprint,
            Long requiredOrderId
    ) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT id,voucher_id
                FROM commerce_order
                WHERE user_id=? AND BINARY idempotency_key=BINARY ?
                """, userId, idempotencyKey);
        if (orders.size() > 1) {
            throw new IllegalStateException("Commerce order idempotency uniqueness is violated");
        }
        if (orders.isEmpty()) return null;

        long existingOrderId = number(orders.get(0), "id");
        long existingVoucherId = number(orders.get(0), "voucher_id");
        String expectedFingerprint = requestFingerprint(existingVoucherId);
        if (existingVoucherId != voucherId
                || !expectedFingerprint.equals(requestFingerprint)
                || requiredOrderId != null && requiredOrderId != existingOrderId) {
            throw idempotencyConflict();
        }

        try {
            jdbc.update("""
                    INSERT INTO commerce_admission_ledger(
                        order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
                        routing_generation,physical_shard_count,status,attempts,
                        accepted_at,terminal_at
                    ) VALUES (?,?,?,?,?, ?,0,'ORDER_CREATED',0,
                              CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                    """, existingOrderId, userId, idempotencyKey, existingVoucherId,
                    expectedFingerprint, LEGACY_ORDER_ROUTING_GENERATION);
        } catch (DataIntegrityViolationException concurrentOrConflictingIdentity) {
            // 只有在下方重读并确认 MySQL 订单身份完全一致后，才接受并发补建导致的唯一键冲突。
        }

        Admission current = find(userId, idempotencyKey);
        if (current == null || !current.sameOrderIdentity(
                existingOrderId,
                existingVoucherId,
                userId,
                idempotencyKey,
                expectedFingerprint
        )) {
            throw durabilityUnavailable(
                    "Existing Commerce order conflicts with its durable admission identity",
                    null
            );
        }
        if (current.status() != Status.ORDER_CREATED) {
            Status previousStatus = current.status();
            int terminalized = jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET status='ORDER_CREATED',
                        accepted_at=COALESCE(accepted_at,CURRENT_TIMESTAMP(3)),
                        terminal_at=COALESCE(terminal_at,CURRENT_TIMESTAMP(3)),
                        rejection_code=NULL,rejection_http_status=NULL,rejection_message=NULL,
                        claim_token=NULL,claim_owner=NULL,claimed_until=NULL,last_error=NULL
                    WHERE order_id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                      AND request_fingerprint=?
                    """, existingOrderId, userId, existingVoucherId, idempotencyKey,
                    expectedFingerprint);
            if (terminalized != 1) {
                throw durabilityUnavailable(
                        "Unable to terminalize the existing Commerce order admission",
                        null
                );
            }
            if (previousStatus == Status.ACCEPTED) {
                acceptedBacklog.updateAndGet(value -> Math.max(0L, value - 1L));
            } else if (previousStatus == Status.REPAIR_REQUIRED) {
                repairRequired.updateAndGet(value -> Math.max(0L, value - 1L));
            }
            current = find(userId, idempotencyKey);
            if (current == null || current.status() != Status.ORDER_CREATED
                    || !current.sameOrderIdentity(
                    existingOrderId,
                    existingVoucherId,
                    userId,
                    idempotencyKey,
                    expectedFingerprint
            )) {
                throw durabilityUnavailable(
                        "Unable to verify the existing Commerce order admission",
                        null
                );
            }
        }
        count(METRIC_DECISION, "legacy_order_materialized");
        return current;
    }

    private String requestFingerprint(long voucherId) {
        return "seckill:v1:" + voucherId;
    }

    private CommerceApiException idempotencyConflict() {
        return new CommerceApiException(
                "COMMERCE_IDEMPOTENCY_CONFLICT",
                "Idempotency key is already in use for another Commerce admission",
                409
        );
    }

    private List<Admission> rows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::admission).toList();
    }

    private Admission admission(Map<String, Object> row) {
        return new Admission(
                number(row, "order_id"),
                number(row, "voucher_id"),
                number(row, "user_id"),
                String.valueOf(value(row, "idempotency_key")),
                String.valueOf(value(row, "request_fingerprint")),
                status(String.valueOf(value(row, "status"))),
                text(row, "rejection_code"),
                integer(row, "rejection_http_status"),
                text(row, "rejection_message"),
                (int) numberOrDefault(row, "attempts", 0L),
                text(row, "claim_token"),
                String.valueOf(value(row, "routing_generation")),
                (int) number(row, "physical_shard_count")
        );
    }

    /**
     * 根据 ACCEPTED 积压、最老年龄和人工修复数量施加准入背压；容量未知时同样失败关闭。
     */
    private void assertRecoveryCapacity() {
        long now = System.nanoTime();
        if (capacityObservedAtNanos == 0L || now - capacityObservedAtNanos >= capacityCacheNanos) {
            try {
                refreshCapacity();
            } catch (RuntimeException failure) {
                throw durabilityUnavailable("Unable to verify Commerce admission recovery capacity", failure);
            }
        }
        if (acceptedBacklog.get() >= maxAcceptedBacklog
                || oldestAcceptedAgeMillis.get() >= maxAcceptedAgeMillis
                || repairRequired.get() > 0L) {
            count(METRIC_DECISION, "capacity_rejected");
            throw new CommerceApiException(
                    "COMMERCE_ADMISSION_BACKLOG_UNHEALTHY",
                    "Commerce admission recovery backlog is above its production threshold",
                    503
            );
        }
    }

    /** 从 MySQL 聚合最新恢复容量；同步块避免并发请求重复执行同一统计。 */
    private synchronized void refreshCapacity() {
        long now = System.nanoTime();
        if (capacityObservedAtNanos != 0L && now - capacityObservedAtNanos < capacityCacheNanos) return;
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                  COALESCE(SUM(CASE WHEN status='ACCEPTED' THEN 1 ELSE 0 END),0) accepted_backlog,
                  COALESCE(TIMESTAMPDIFF(
                    MICROSECOND,
                    MIN(CASE WHEN status='ACCEPTED' THEN accepted_at END),
                    CURRENT_TIMESTAMP(3)
                  ) DIV 1000,0) oldest_accepted_age_ms,
                  COALESCE(SUM(CASE WHEN status='REPAIR_REQUIRED' THEN 1 ELSE 0 END),0) repair_required
                FROM commerce_admission_ledger
                WHERE status IN ('ACCEPTED','REPAIR_REQUIRED')
                """);
        acceptedBacklog.set(numberOrDefault(row, "accepted_backlog", 0L));
        oldestAcceptedAgeMillis.set(numberOrDefault(row, "oldest_accepted_age_ms", 0L));
        repairRequired.set(numberOrDefault(row, "repair_required", 0L));
        capacityObservedAtNanos = now;
    }

    private CommerceApiException replayRejection(Admission admission) {
        String code = admission.rejectionCode() == null
                ? "COMMERCE_ADMISSION_REJECTED"
                : admission.rejectionCode();
        int status = admission.rejectionHttpStatus() >= 400
                && admission.rejectionHttpStatus() <= 599
                ? admission.rejectionHttpStatus()
                : 409;
        String message = admission.rejectionMessage() == null
                ? "Commerce admission was rejected"
                : admission.rejectionMessage();
        return new CommerceApiException(code, message, status);
    }

    private CommerceApiException durabilityUnavailable(String message, Throwable cause) {
        CommerceApiException error = new CommerceApiException(
                CommerceAdmissionOutcomePolicy.COMMERCE_DURABLE_ADMISSION_UNAVAILABLE,
                message,
                503
        );
        if (cause != null) error.initCause(cause);
        return error;
    }

    private void count(String metric, String outcome) {
        metrics.counter(metric, "outcome", outcome).increment();
    }

    private Status status(String raw) {
        try {
            return Status.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("Unknown Commerce admission status: " + raw, unknown);
        }
    }

    private Object value(Map<String, Object> row, String key) {
        Object exact = row.get(key);
        if (exact != null || row.containsKey(key)) return exact;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private long number(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Missing numeric admission column " + key);
        }
        return number.longValue();
    }

    private long numberOrDefault(Map<String, Object> row, String key, long fallback) {
        Object value = value(row, key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private int integer(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    /** 持久化准入账本状态机。 */
    enum Status {
        /** 已保留身份，尚未证明 Redis Voucher 本地准入成功。 */
        PREPARED,
        /** Redis 准入已证明并持久化，订单可能仍在异步处理中。 */
        ACCEPTED,
        /** 可稳定重放的业务拒绝。 */
        REJECTED,
        /** MySQL 订单已存在的终态。 */
        ORDER_CREATED,
        /** 自动恢复已停止，等待运维处理。 */
        REPAIR_REQUIRED
    }

    /**
     * 一条持久化准入身份及其恢复所有权快照。
     *
     * @param orderId 预留订单 ID
     * @param voucherId Voucher ID
     * @param userId 用户 ID
     * @param idempotencyKey 用户幂等键
     * @param requestFingerprint 请求业务语义指纹
     * @param status 当前账本状态
     * @param rejectionCode 稳定拒绝错误码
     * @param rejectionHttpStatus 稳定拒绝 HTTP 状态
     * @param rejectionMessage 稳定拒绝消息
     * @param attempts 自动恢复尝试次数
     * @param claimToken 当前恢复 claim；未领取时为空
     * @param routingGeneration 该准入所属 Redis 路由代际
     * @param routingShardCount 该准入所属分片配置
     */
    record Admission(
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey,
            String requestFingerprint,
            Status status,
            String rejectionCode,
            int rejectionHttpStatus,
            String rejectionMessage,
            int attempts,
            String claimToken,
            String routingGeneration,
            int routingShardCount
    ) {
        boolean wasAccepted() {
            return status == Status.ACCEPTED || status == Status.ORDER_CREATED;
        }

        boolean sameIdentity(Admission other) {
            return orderId == other.orderId
                    && voucherId == other.voucherId
                    && userId == other.userId
                    && idempotencyKey.equals(other.idempotencyKey)
                    && requestFingerprint.equals(other.requestFingerprint)
                    && routingGeneration.equals(other.routingGeneration)
                    && routingShardCount == other.routingShardCount;
        }

        boolean sameOrderIdentity(
                long expectedOrderId,
                long expectedVoucherId,
                long expectedUserId,
                String expectedIdempotencyKey,
                String expectedFingerprint
        ) {
            return orderId == expectedOrderId
                    && voucherId == expectedVoucherId
                    && userId == expectedUserId
                    && idempotencyKey.equals(expectedIdempotencyKey)
                    && requestFingerprint.equals(expectedFingerprint);
        }

        boolean matchesRouting(String generation, int shardCount) {
            return routingGeneration.equals(generation) && routingShardCount == shardCount;
        }
    }
}
