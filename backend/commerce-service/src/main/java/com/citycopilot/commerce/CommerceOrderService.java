package com.citycopilot.commerce;

import com.citycopilot.contract.event.OrderCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommerceOrderService {
    private static final Logger log = LoggerFactory.getLogger(CommerceOrderService.class);
    static final String STOCK_PREFIX = "commerce:seckill:stock:";
    static final String BUYER_PREFIX = "commerce:seckill:buyers:";
    static final String IDEMPOTENCY_PREFIX = "commerce:seckill:idempotency:";
    static final String FENCE_PREFIX = "commerce:seckill:fence:";
    public static final String INFLIGHT_PREFIX = "commerce:seckill:inflight:";
    static final String RESTORE_MARKER_PREFIX = "commerce:seckill:restore:";
    static final String COMPENSATION_MARKER_PREFIX = "commerce:seckill:compensation:";
    static final String STREAM = "commerce:stream:orders";
    static final String GROUP = "commerce-order-workers";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = script("lua/commerce_seckill.lua");
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = script("lua/commerce_compensate.lua");
    private static final DefaultRedisScript<Long> FINALIZE_ORDER_SCRIPT =
            script("lua/commerce_finalize_order.lua");
    private static final DefaultRedisScript<Long> RESTORE_INVENTORY_SCRIPT =
            script("lua/commerce_restore_inventory.lua");
    private static final DefaultRedisScript<Long> RELEASE_FENCE_SCRIPT =
            script("lua/commerce_release_fence.lua");
    private static final Duration PENDING_MIN_IDLE = Duration.ofSeconds(30);
    private static final Duration INVENTORY_FENCE_TTL = Duration.ofSeconds(30);
    private static final String PHASE_RECEIVED = "RECEIVED";
    private static final String PHASE_COMPENSATION_REQUIRED = "COMPENSATION_REQUIRED";
    private static final String PHASE_ORDER_CREATED = "ORDER_CREATED";
    private static final String PHASE_DEAD_LETTERED = "DEAD_LETTERED";
    private static final String PHASE_REPAIR_REQUIRED = "REPAIR_REQUIRED";
    private static final String PHASE_RESTORE_PENDING = "RESTORE_PENDING";
    private static final String PHASE_RESTORED = "RESTORED";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final CommerceOrderIdGenerator orderIds;
    private final String consumerName;
    private final boolean streamEnabled;
    private final int streamBatchSize;

    public CommerceOrderService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            CommerceOrderIdGenerator orderIds,
            @Value("${citycopilot.commerce.consumer-name:commerce-local}") String consumerName,
            @Value("${citycopilot.commerce.stream-enabled:true}") boolean streamEnabled,
            @Value("${citycopilot.commerce.stream-batch-size:100}") int streamBatchSize
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.orderIds = orderIds;
        this.consumerName = consumerName + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.streamEnabled = streamEnabled;
        this.streamBatchSize = Math.max(1, streamBatchSize);
    }

    @PostConstruct
    void initializeStream() {
        if (!streamEnabled) return;
        RecordId bootstrap = null;
        try {
            bootstrap = redis.opsForStream().add(STREAM, Map.of("bootstrap", "true"));
            redis.opsForStream().createGroup(STREAM, ReadOffset.latest(), GROUP);
        } catch (Exception ignored) {
            // Group already exists.
        } finally {
            if (bootstrap != null) {
                redis.opsForStream().delete(STREAM, bootstrap);
            }
        }
    }

    public Map<String, Object> seckill(long voucherId, long userId, String idempotencyKey) {
        requirePositive(voucherId, "voucherId");
        requirePositive(userId, "userId");
        String key = normalizeIdempotencyKey(idempotencyKey, UUID.randomUUID().toString());
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id,voucher_id,status,version FROM commerce_order WHERE user_id=? AND idempotency_key=? LIMIT 1",
                userId,
                key
        );
        if (!existing.isEmpty()) {
            long existingVoucherId = ((Number) existing.get(0).get("voucher_id")).longValue();
            if (existingVoucherId != voucherId) {
                throw idempotencyConflict();
            }
            return orderResult(existing.get(0), "IDEMPOTENT_REPLAY");
        }
        String admissionKey = admissionKey(userId, key);
        AdmissionIdentity pending = admissionIdentity(redis.opsForValue().get(admissionKey));
        if (pending != null) {
            if (pending.voucherId() != voucherId) {
                throw idempotencyConflict();
            }
            return accepted(pending.orderId(), key, "IDEMPOTENT_REPLAY");
        }
        long orderId = orderIds.nextId();
        return admit(voucherId, userId, orderId, key);
    }

    Map<String, Object> replayAdmission(
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        requirePositive(orderId, "orderId");
        requirePositive(voucherId, "voucherId");
        requirePositive(userId, "userId");
        String key = normalizeIdempotencyKey(idempotencyKey, "dead-letter-order:" + orderId);
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id,status,version,user_id,voucher_id,idempotency_key
                FROM commerce_order
                WHERE id=? OR (user_id=? AND idempotency_key=?)
                LIMIT 1
                """, orderId, userId, key);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            boolean identical = ((Number) row.get("id")).longValue() == orderId
                    && ((Number) row.get("user_id")).longValue() == userId
                    && ((Number) row.get("voucher_id")).longValue() == voucherId
                    && key.equals(String.valueOf(row.get("idempotency_key")));
            if (!identical) {
                throw new CommerceApiException(
                        "COMMERCE_REPLAY_IDENTITY_CONFLICT",
                        "Dead-letter order identity conflicts with an existing order",
                        409
                );
            }
            return orderResult(row, "IDEMPOTENT_REPLAY");
        }
        return admit(voucherId, userId, orderId, key);
    }

    private Map<String, Object> admit(
            long voucherId,
            long userId,
            long orderId,
            String idempotencyKey
    ) {
        String admissionKey = admissionKey(userId, idempotencyKey);
        Long result;
        try {
            result = transactions.execute(status -> {
                validateVoucherForAdmission(voucherId, Instant.now());
                return redis.execute(
                        SECKILL_SCRIPT,
                        List.of(
                                STOCK_PREFIX + voucherId,
                                BUYER_PREFIX + voucherId,
                                STREAM,
                                admissionKey,
                                FENCE_PREFIX + voucherId,
                                inflightKey(voucherId)
                        ),
                        String.valueOf(userId),
                        String.valueOf(orderId),
                        String.valueOf(voucherId),
                        idempotencyKey
                );
            });
        } catch (CommerceApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new CommerceApiException(
                    "COMMERCE_REDIS_UNAVAILABLE",
                    "Seckill admission unavailable",
                    503
            );
        }
        if (result == null || result == 5L) {
            throw new CommerceApiException("COMMERCE_REDIS_UNAVAILABLE", "Seckill admission unavailable", 503);
        }
        if (result == 1L) {
            throw new CommerceApiException("SECKILL_STOCK_NOT_ENOUGH", "Voucher stock exhausted", 409);
        }
        if (result == 2L) {
            throw new CommerceApiException("SECKILL_DUPLICATE_ORDER", "User already purchased this voucher", 409);
        }
        if (result == 4L) {
            AdmissionIdentity existing = admissionIdentity(redis.opsForValue().get(admissionKey));
            if (existing != null && existing.voucherId() == voucherId) {
                return accepted(existing.orderId(), idempotencyKey, "IDEMPOTENT_REPLAY");
            }
            throw idempotencyConflict();
        }
        if (result == 6L) {
            throw new CommerceApiException(
                    "COMMERCE_INVENTORY_MAINTENANCE",
                    "Voucher inventory is under maintenance",
                    503
            );
        }
        return accepted(
                orderId,
                idempotencyKey,
                result == 3L ? "IDEMPOTENT_REPLAY" : "COMMERCE_ORDER_ACCEPTED"
        );
    }

    @Scheduled(fixedDelayString = "${citycopilot.commerce.stream-poll-ms:50}")
    public void consumeOrders() {
        if (!streamEnabled) return;
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redis.opsForStream().read(
                    Consumer.from(GROUP, consumerName),
                    StreamReadOptions.empty().count(streamBatchSize).block(Duration.ofMillis(250)),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed())
            );
        } catch (Exception unavailable) {
            return;
        }
        if (records == null) return;
        for (MapRecord<String, Object, Object> record : records) {
            processSafely(record);
        }
    }

    @Scheduled(
            fixedDelayString = "${citycopilot.commerce.pending-reclaim-ms:5000}",
            initialDelayString = "${citycopilot.commerce.pending-reclaim-ms:5000}"
    )
    public void reclaimPendingOrders() {
        if (!streamEnabled) return;
        try {
            PendingMessages pending = redis.opsForStream().pending(
                    STREAM,
                    GROUP,
                    Range.unbounded(),
                    streamBatchSize
            );
            List<RecordId> stale = new ArrayList<>();
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().compareTo(PENDING_MIN_IDLE) >= 0) {
                    stale.add(message.getId());
                }
            }
            if (stale.isEmpty()) return;
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    STREAM,
                    GROUP,
                    consumerName,
                    PENDING_MIN_IDLE,
                    stale.toArray(RecordId[]::new)
            );
            for (MapRecord<String, Object, Object> record : claimed) {
                processSafely(record);
            }
        } catch (Exception unavailable) {
            log.warn("Unable to reclaim pending Commerce orders: {}", unavailable.getMessage());
        }
    }

    private void processSafely(MapRecord<String, Object, Object> record) {
        try {
            process(record);
        } catch (RuntimeException malformed) {
            // One malformed record must not prevent the remaining batch from
            // progressing. Keep it pending for explicit operator recovery.
            log.error(
                    "Unable to parse Commerce order record {}; leaving it pending",
                    record.getId().getValue(),
                    malformed
            );
        }
    }

    void process(MapRecord<String, Object, Object> record) {
        if ("true".equals(String.valueOf(record.getValue().get("bootstrap")))) {
            try {
                redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
            } catch (RuntimeException unavailable) {
                log.warn("Unable to acknowledge Commerce stream bootstrap record {}", record.getId());
            }
            return;
        }
        long orderId = number(record, "orderId");
        long userId = number(record, "userId");
        long voucherId = number(record, "voucherId");
        String idempotencyKey = text(record, "idempotencyKey");
        String sourceId = record.getId().getValue();
        try {
            ensureRecoveryRecord(sourceId, orderId, voucherId, userId, idempotencyKey, record.getValue());
        } catch (Exception persistenceFailure) {
            log.error("Unable to initialize Commerce recovery row for {}; leaving it pending", sourceId, persistenceFailure);
            return;
        }
        String phase = recoveryPhase(sourceId);
        if (isStreamTerminal(phase)) {
            finalizeStreamRecord(sourceId, orderId, voucherId);
            return;
        }
        String claimToken = claimRecovery(sourceId);
        if (claimToken == null) {
            return;
        }
        try {
            phase = recoveryPhase(sourceId);
            if (PHASE_RECEIVED.equals(phase)) {
                try {
                    createOrder(sourceId, claimToken, orderId, voucherId, userId, idempotencyKey);
                } catch (DuplicateKeyException duplicate) {
                    Integer identicalOrder = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM commerce_order
                            WHERE id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                            """, Integer.class, orderId, userId, voucherId, idempotencyKey);
                    if (identicalOrder != null && identicalOrder == 1) {
                        markExistingOrderCreated(sourceId, claimToken);
                    } else {
                        markCompensationRequired(sourceId, claimToken, duplicate);
                    }
                } catch (Exception failure) {
                    markCompensationRequired(sourceId, claimToken, failure);
                }
                phase = recoveryPhase(sourceId);
            }
            if (PHASE_COMPENSATION_REQUIRED.equals(phase)) {
                completeRecovery(sourceId, claimToken, orderId, voucherId, userId, idempotencyKey);
            }
            phase = recoveryPhase(sourceId);
            if (isStreamTerminal(phase)) {
                finalizeStreamRecord(sourceId, orderId, voucherId);
            }
        } catch (LostRecoveryClaim lostClaim) {
            log.debug("Commerce recovery ownership changed for {}", sourceId);
        } catch (Exception recoveryFailure) {
            releaseRecoveryClaim(sourceId, claimToken);
            log.error("Unable to finish Commerce order record {}; leaving it pending", sourceId, recoveryFailure);
        }
    }

    private void createOrder(
            String sourceId,
            String claimToken,
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        transactions.executeWithoutResult(status -> {
            requireOwnedPhase(sourceId, claimToken, PHASE_RECEIVED);
            jdbc.update("""
                    INSERT INTO commerce_reservation(
                        order_id,voucher_id,user_id,status,stream_record_id
                    ) VALUES (?,?,?,'RESERVED',?)
                    """, orderId, voucherId, userId, sourceId);
            Integer updated = jdbc.update("""
                    UPDATE commerce_seckill_voucher
                    SET stock=stock-1, version=version+1
                    WHERE voucher_id=? AND stock>0
                    """, voucherId);
            if (updated != 1) {
                throw new CommerceApiException(
                        "COMMERCE_DATABASE_STOCK_EXHAUSTED",
                        "Database stock exhausted after Redis admission",
                        409
                );
            }
            Map<String, Object> voucher = jdbc.queryForMap("""
                    SELECT v.id, v.shop_id, v.title, v.sub_title, v.rules,
                           v.pay_value, v.actual_value, p.shop_name
                    FROM commerce_voucher v
                    LEFT JOIN shop_summary_projection p ON p.shop_id=v.shop_id
                    WHERE v.id=?
                    """, voucherId);
            jdbc.update("""
                    INSERT INTO commerce_order(
                        id,user_id,voucher_id,shop_id,shop_name_snapshot,
                        voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
                        pay_value_snapshot,actual_value_snapshot,status,version,idempotency_key
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,1,0,?)
                    """,
                    orderId, userId, voucherId, voucher.get("shop_id"),
                    value(voucher.get("shop_name")), value(voucher.get("title")),
                    value(voucher.get("sub_title")), value(voucher.get("rules")),
                    voucher.get("pay_value"), voucher.get("actual_value"), idempotencyKey
            );
            jdbc.update("""
                    UPDATE commerce_reservation
                    SET status='ORDER_CREATED', version=version+1
                    WHERE order_id=? AND status='RESERVED'
                    """, orderId);
            Map<String, Object> event = new LinkedHashMap<>();
            Instant occurredAt = Instant.now();
            OrderCreatedEvent payload = new OrderCreatedEvent(
                    orderId,
                    userId,
                    voucherId,
                    1,
                    0,
                    occurredAt
            );
            event.put("eventId", UUID.randomUUID().toString());
            event.put("eventType", OrderCreatedEvent.EVENT_TYPE);
            event.put("schemaVersion", OrderCreatedEvent.SCHEMA_VERSION);
            event.put("aggregateType", "commerce_order");
            event.put("aggregateId", String.valueOf(orderId));
            event.put("aggregateVersion", 0);
            event.put("sourceService", "commerce-service");
            event.put("occurredAt", occurredAt.toString());
            event.put("correlationId", String.valueOf(orderId));
            event.put("payload", payload);
            jdbc.update("""
                    INSERT INTO commerce_outbox_event(
                        event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                        topic,payload_json,status
                    ) VALUES (?,?,?,?,?,'citycopilot-commerce-events-v1',?,'READY')
                    """,
                    event.get("eventId"), event.get("eventType"), event.get("aggregateType"),
                    event.get("aggregateId"), event.get("aggregateVersion"), json(event)
            );
            int terminal = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, terminal_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL, claim_owner=NULL, claimed_until=NULL
                    WHERE source_id=? AND claim_token=? AND phase=?
                    """, PHASE_ORDER_CREATED, sourceId, claimToken, PHASE_RECEIVED);
            if (terminal != 1) throw new LostRecoveryClaim();
        });
    }

    private void markExistingOrderCreated(String sourceId, String claimToken) {
        transactions.executeWithoutResult(status -> {
            requireOwnedPhase(sourceId, claimToken, PHASE_RECEIVED);
            int terminal = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, terminal_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL, claim_owner=NULL, claimed_until=NULL
                    WHERE source_id=? AND claim_token=? AND phase=?
                    """, PHASE_ORDER_CREATED, sourceId, claimToken, PHASE_RECEIVED);
            if (terminal != 1) throw new LostRecoveryClaim();
        });
    }

    private void markCompensationRequired(String sourceId, String claimToken, Exception failure) {
        transactions.executeWithoutResult(status -> {
            requireOwnedPhase(sourceId, claimToken, PHASE_RECEIVED);
            int changed = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, last_error=?
                    WHERE source_id=? AND claim_token=? AND phase=?
                    """, PHASE_COMPENSATION_REQUIRED, truncate(failure.getMessage()),
                    sourceId, claimToken, PHASE_RECEIVED);
            if (changed != 1) throw new LostRecoveryClaim();
        });
    }

    void completeRecovery(
            String sourceId,
            String claimToken,
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        transactions.executeWithoutResult(status -> {
            requireOwnedPhase(sourceId, claimToken, PHASE_COMPENSATION_REQUIRED);
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

    private void requireOwnedPhase(String sourceId, String claimToken, String phase) {
        int owned = jdbc.update("""
                UPDATE commerce_order_recovery
                SET claimed_until=TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(3))
                WHERE source_id=? AND claim_token=? AND phase=?
                """, sourceId, claimToken, phase);
        if (owned != 1) throw new LostRecoveryClaim();
    }

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

    void finalizeStreamRecord(String sourceId, long orderId, long voucherId) {
        try {
            Long result = redis.execute(
                    FINALIZE_ORDER_SCRIPT,
                    List.of(
                            inflightKey(voucherId),
                            STREAM,
                            COMPENSATION_MARKER_PREFIX + sourceId
                    ),
                    inflightMember(sourceId, orderId),
                    sourceId,
                    GROUP
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
            // The script is idempotent. If the connection failed after Redis
            // applied it, no redelivery occurs; otherwise XCLAIM retries only
            // this finalization phase because the DB phase is already terminal.
            log.warn("Unable to finalize Commerce order record {}; it remains recoverable", sourceId);
        }
    }

    public Map<String, Object> order(long orderId, long userId, boolean admin) {
        String sql = """
                SELECT id,user_id,voucher_id,shop_id,shop_name_snapshot,
                       voucher_title_snapshot,voucher_sub_title_snapshot,voucher_rules_snapshot,
                       pay_value_snapshot,actual_value_snapshot,status,version,
                       create_time,pay_time,use_time,refund_time,update_time
                FROM commerce_order WHERE id=?
                """ + (admin ? "" : " AND user_id=?");
        List<Map<String, Object>> rows = admin
                ? jdbc.queryForList(sql, orderId)
                : jdbc.queryForList(sql, orderId, userId);
        if (rows.isEmpty()) {
            throw new CommerceApiException("COMMERCE_ORDER_NOT_FOUND", "Order not found", 404);
        }
        return normalizeOrder(rows.get(0));
    }

    public List<Map<String, Object>> myOrders(long userId, int page) {
        return jdbc.queryForList("""
                SELECT id,user_id,voucher_id,shop_id,shop_name_snapshot,
                       voucher_title_snapshot,status,version,create_time,update_time
                FROM commerce_order
                WHERE user_id=?
                ORDER BY id DESC
                LIMIT 10 OFFSET ?
                """, userId, Math.max(0, page - 1) * 10).stream().map(this::normalizeOrder).toList();
    }

    public Map<String, Object> transition(long orderId, long userId, String action, Integer payType, boolean admin) {
        Map<String, Transition> transitions = Map.of(
                "pay", new Transition(1, 2),
                "cancel", new Transition(1, 4),
                "use", new Transition(2, 3),
                "refund", new Transition(2, 6)
        );
        Transition transition = transitions.get(action);
        if (transition == null) {
            throw new CommerceApiException("COMMERCE_ACTION_UNSUPPORTED", "Unsupported order action", 400);
        }
        String ownerClause = admin ? "" : " AND user_id=?";
        String timeColumn = switch (action) {
            case "pay" -> "pay_time";
            case "use" -> "use_time";
            case "refund" -> "refund_time";
            default -> "update_time";
        };
        String sql = "UPDATE commerce_order SET status=?, version=version+1, "
                + timeColumn + "=CURRENT_TIMESTAMP(3)"
                + ("pay".equals(action) ? ", pay_type=?" : "")
                + " WHERE id=? AND status=?" + ownerClause;
        Map<String, Object> before = order(orderId, userId, admin);
        long voucherId = ((Number) before.get("voucherId")).longValue();
        boolean returnsInventory = "cancel".equals(action) || "refund".equals(action);
        String fenceToken = returnsInventory ? acquireInventoryFence(voucherId) : null;
        String recoverySourceId = returnsInventory
                ? "transition:" + orderId + ":" + action + ":" + before.get("version")
                : null;
        boolean transitionCommitted = false;
        try {
            Boolean changed = transactions.execute(status -> {
                int updated;
                if ("pay".equals(action)) {
                    updated = admin
                            ? jdbc.update(sql, transition.to(), payType == null ? 1 : payType, orderId, transition.from())
                            : jdbc.update(sql, transition.to(), payType == null ? 1 : payType, orderId, transition.from(), userId);
                } else {
                    updated = admin
                            ? jdbc.update(sql, transition.to(), orderId, transition.from())
                            : jdbc.update(sql, transition.to(), orderId, transition.from(), userId);
                }
                if (updated != 1) return false;
                if (returnsInventory) {
                    jdbc.update("""
                            UPDATE commerce_seckill_voucher
                            SET stock=stock+1, version=version+1
                            WHERE voucher_id=?
                            """, voucherId);
                    jdbc.update("""
                            INSERT INTO commerce_order_recovery(
                                source_id,source_type,order_id,voucher_id,user_id,payload_json,
                                phase,inventory_fence_token,compensation_delta
                            ) VALUES (?,'STATE_TRANSITION',?,?,?,?,?,?,1)
                            """,
                            recoverySourceId, orderId, voucherId, userId,
                            json(Map.of("action", action, "targetStatus", transition.to())),
                            PHASE_RESTORE_PENDING, fenceToken
                    );
                }
                appendStateEvent(orderId, userId, action, transition.to());
                return true;
            });
            if (!Boolean.TRUE.equals(changed)) {
                throw new CommerceApiException(
                        "COMMERCE_ORDER_STATE_CONFLICT",
                        "Order state changed or action is not allowed",
                        409
                );
            }
            transitionCommitted = true;
            String inventoryRecovery = null;
            if (returnsInventory) {
                inventoryRecovery = attemptTransitionInventoryRestore(
                        recoverySourceId,
                        orderId,
                        voucherId,
                        fenceToken
                );
            }
            Map<String, Object> result = order(orderId, userId, admin);
            if (inventoryRecovery != null && !PHASE_RESTORED.equals(inventoryRecovery)) {
                result.put("inventoryRecovery", inventoryRecovery);
            }
            return result;
        } finally {
            if (fenceToken != null && !transitionCommitted) {
                releaseInventoryFence(voucherId, fenceToken);
            }
        }
    }

    @Scheduled(
            fixedDelayString = "${citycopilot.commerce.inventory-recovery-ms:5000}",
            initialDelayString = "${citycopilot.commerce.inventory-recovery-ms:5000}"
    )
    public void resumePendingInventoryRestores() {
        List<Map<String, Object>> pending;
        try {
            pending = jdbc.queryForList("""
                    SELECT source_id,order_id,voucher_id,inventory_fence_token
                    FROM commerce_order_recovery
                    WHERE source_type='STATE_TRANSITION' AND phase=?
                    ORDER BY created_at
                    LIMIT 100
                    """, PHASE_RESTORE_PENDING);
        } catch (Exception unavailable) {
            return;
        }
        for (Map<String, Object> row : pending) {
            try {
                attemptTransitionInventoryRestore(
                        String.valueOf(row.get("source_id")),
                        ((Number) row.get("order_id")).longValue(),
                        ((Number) row.get("voucher_id")).longValue(),
                        String.valueOf(row.get("inventory_fence_token"))
                );
            } catch (Exception failure) {
                log.warn("Unable to resume inventory restore for {}", row.get("source_id"));
            }
        }
    }

    String attemptTransitionInventoryRestore(
            String sourceId,
            long orderId,
            long voucherId,
            String fenceToken
    ) {
        Long restored;
        try {
            restored = redis.execute(
                    RESTORE_INVENTORY_SCRIPT,
                    List.of(
                            FENCE_PREFIX + voucherId,
                            STOCK_PREFIX + voucherId,
                            RESTORE_MARKER_PREFIX + sourceId
                    ),
                    fenceToken
            );
        } catch (Exception unavailable) {
            // Keep RESTORE_PENDING. The scheduler can safely retry because the
            // Lua marker distinguishes a completed increment from no attempt.
            log.warn("Inventory restore remains pending for order {}", orderId);
            return PHASE_RESTORE_PENDING;
        }
        if (restored != null && (restored == 1L || restored == 2L)) {
            int completed = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, compensation_delta=0, terminal_at=CURRENT_TIMESTAMP(3)
                    WHERE source_id=? AND phase=?
                    """, PHASE_RESTORED, sourceId, PHASE_RESTORE_PENDING);
            if (completed == 1) {
                try {
                    redis.delete(RESTORE_MARKER_PREFIX + sourceId);
                } catch (Exception cleanupFailure) {
                    log.debug("Unable to remove completed inventory restore marker {}", sourceId);
                }
            }
            return PHASE_RESTORED;
        }
        String reason = restored != null && restored == -2L
                ? "redis_stock_missing_during_transition_restore"
                : "inventory_fence_expired_before_transition_restore";
        jdbc.update("""
                UPDATE commerce_order_recovery
                SET phase=?, compensation_delta=1, last_error=?,
                    terminal_at=CURRENT_TIMESTAMP(3)
                WHERE source_id=? AND phase=?
                """, PHASE_REPAIR_REQUIRED, reason, sourceId, PHASE_RESTORE_PENDING);
        releaseInventoryFence(voucherId, fenceToken);
        return PHASE_REPAIR_REQUIRED;
    }

    private void appendStateEvent(long orderId, long userId, String action, int state) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "commerce.order." + action);
        event.put("schemaVersion", 1);
        event.put("aggregateType", "commerce_order");
        event.put("aggregateId", String.valueOf(orderId));
        event.put("aggregateVersion", state);
        event.put("sourceService", "commerce-service");
        event.put("occurredAt", Instant.now().toString());
        event.put("correlationId", String.valueOf(orderId));
        event.put("payload", Map.of(
                "orderId", orderId,
                "userId", userId,
                "action", action,
                "status", state
        ));
        jdbc.update("""
                INSERT INTO commerce_outbox_event(
                    event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                    topic,payload_json,status
                ) VALUES (?,?,?,?,?,'citycopilot-commerce-events-v1',?,'READY')
                """,
                eventId,
                event.get("eventType"),
                event.get("aggregateType"),
                event.get("aggregateId"),
                event.get("aggregateVersion"),
                json(event)
        );
    }

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
                    WHERE user_id=? AND voucher_id=? AND id<>?
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
                        STOCK_PREFIX + voucherId,
                        BUYER_PREFIX + voucherId,
                        admissionKey(userId, idempotencyKey),
                        COMPENSATION_MARKER_PREFIX + sourceId
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

    private String acquireInventoryFence(long voucherId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(
                FENCE_PREFIX + voucherId,
                token,
                INVENTORY_FENCE_TTL
        );
        if (!Boolean.TRUE.equals(acquired)) {
            throw new CommerceApiException(
                    "COMMERCE_INVENTORY_MAINTENANCE",
                    "Voucher inventory is under maintenance",
                    409
            );
        }
        return token;
    }

    private void releaseInventoryFence(long voucherId, String token) {
        try {
            redis.execute(
                    RELEASE_FENCE_SCRIPT,
                    List.of(FENCE_PREFIX + voucherId),
                    token
            );
        } catch (Exception failure) {
            log.warn(
                    "Unable to release inventory fence for voucher {}; TTL recovery remains active",
                    voucherId
            );
        }
    }

    private Map<String, Object> normalizeOrder(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        rename(result, "user_id", "userId");
        rename(result, "voucher_id", "voucherId");
        rename(result, "shop_id", "shopId");
        rename(result, "pay_type", "payType");
        rename(result, "create_time", "createTime");
        rename(result, "pay_time", "payTime");
        rename(result, "use_time", "useTime");
        rename(result, "refund_time", "refundTime");
        rename(result, "update_time", "updateTime");
        rename(result, "shop_name_snapshot", "shopName");
        rename(result, "voucher_title_snapshot", "voucherTitle");
        rename(result, "voucher_sub_title_snapshot", "voucherSubTitle");
        rename(result, "voucher_rules_snapshot", "voucherRules");
        rename(result, "pay_value_snapshot", "payValue");
        rename(result, "actual_value_snapshot", "actualValue");
        if (result.get("id") != null) {
            result.put("id", String.valueOf(result.get("id")));
        }
        return result;
    }

    private Map<String, Object> orderResult(Map<String, Object> row, String code) {
        return Map.of(
                "orderId", String.valueOf(row.get("id")),
                "status", row.get("status"),
                "version", row.get("version"),
                "state", "completed",
                "code", code
        );
    }

    private Map<String, Object> accepted(long orderId, String idempotencyKey, String code) {
        return Map.of(
                "orderId", String.valueOf(orderId),
                "state", "processing",
                "code", code,
                "idempotencyKey", idempotencyKey
        );
    }

    private String admissionKey(long userId, String idempotencyKey) {
        return IDEMPOTENCY_PREFIX + userId + ":" + idempotencyKey;
    }

    public static String inflightKey(long voucherId) {
        return INFLIGHT_PREFIX + voucherId;
    }

    static String inflightMember(String sourceId, long orderId) {
        return sourceId + "|" + orderId;
    }

    private AdmissionIdentity admissionIdentity(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split("\\|", 2);
        if (parts.length != 2) {
            // Old numeric-only admissions cannot prove voucher identity and
            // must not be treated as a replay for an arbitrary voucher.
            return null;
        }
        try {
            return new AdmissionIdentity(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private CommerceApiException idempotencyConflict() {
        return new CommerceApiException(
                "COMMERCE_IDEMPOTENCY_CONFLICT",
                "Idempotency key is already in use for another voucher",
                409
        );
    }

    private String normalizeIdempotencyKey(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.length() > 128) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "Idempotency-Key must not exceed 128 characters",
                    400
            );
        }
        return normalized;
    }

    private void validateVoucherForAdmission(long voucherId, Instant now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT v.id,v.type,v.status,s.stock,s.begin_time,s.end_time
                FROM commerce_voucher v
                LEFT JOIN commerce_seckill_voucher s ON s.voucher_id=v.id
                WHERE v.id=?
                FOR UPDATE
                """, voucherId);
        if (rows.isEmpty()) {
            throw new CommerceApiException("VOUCHER_NOT_FOUND", "Voucher not found", 404);
        }
        validateAdmissionConfig(rows.get(0), now);
    }

    static void validateAdmissionConfig(Map<String, Object> voucher, Instant now) {
        if (((Number) voucher.get("type")).intValue() != 1) {
            throw new CommerceApiException("VOUCHER_NOT_SECKILL", "Voucher is not a seckill voucher", 409);
        }
        if (((Number) voucher.get("status")).intValue() != 1) {
            throw new CommerceApiException("VOUCHER_NOT_AVAILABLE", "Voucher is not active", 409);
        }
        Object stockValue = voucher.get("stock");
        if (!(stockValue instanceof Number stock) || stock.intValue() <= 0) {
            throw new CommerceApiException("SECKILL_STOCK_NOT_ENOUGH", "Voucher stock exhausted", 409);
        }
        Instant begin = instant(voucher.get("begin_time"));
        Instant end = instant(voucher.get("end_time"));
        if (begin == null || end == null) {
            throw new CommerceApiException(
                    "VOUCHER_NOT_AVAILABLE",
                    "Seckill voucher schedule is incomplete",
                    409
            );
        }
        if (now.isBefore(begin)) {
            throw new CommerceApiException("SECKILL_NOT_STARTED", "Seckill has not started", 409);
        }
        if (now.isAfter(end)) {
            throw new CommerceApiException("SECKILL_ENDED", "Seckill has ended", 409);
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof LocalDateTime local) {
            return local.atZone(ZoneId.systemDefault()).toInstant();
        }
        return value == null ? null : Instant.parse(String.valueOf(value));
    }

    private void rename(Map<String, Object> row, String from, String to) {
        if (row.containsKey(from)) row.put(to, row.remove(from));
    }

    private long number(MapRecord<String, Object, Object> record, String key) {
        return Long.parseLong(text(record, key));
    }

    private String text(MapRecord<String, Object, Object> record, String key) {
        Object value = record.getValue().get(key);
        if (value == null) throw new IllegalArgumentException("Stream field missing: " + key);
        return String.valueOf(value);
    }

    private void requirePositive(long value, String name) {
        if (value <= 0) throw new CommerceApiException("COMMERCE_INVALID_ARGUMENT", name + " must be positive", 400);
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private record Transition(int from, int to) {}

    private record AdmissionIdentity(long orderId, long voucherId) {}

    private static final class LostRecoveryClaim extends RuntimeException {
    }
}
