package com.citycopilot.commerce;

import com.citycopilot.contract.event.OrderCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CommerceOrderService {
    private static final Logger log = LoggerFactory.getLogger(CommerceOrderService.class);
    static final String STOCK_PREFIX = "commerce:{commerce:0:1:0}:seckill:stock:";
    static final String BUYER_PREFIX = "commerce:{commerce:0:1:0}:seckill:buyers:";
    static final String IDEMPOTENCY_PREFIX = "commerce:{commerce:0:1:0}:seckill:idempotency:";
    static final String FENCE_PREFIX = "commerce:{commerce:0:1:0}:seckill:fence:";
    static final String INFLIGHT_PREFIX = "commerce:{commerce:0:1:0}:seckill:inflight:";
    static final String RESTORE_MARKER_PREFIX = "commerce:{commerce:0:1:0}:seckill:restore:";
    static final String COMPENSATION_MARKER_PREFIX = "commerce:{commerce:0:1:0}:seckill:compensation:";
    static final String STREAM = "commerce:{commerce:0:1:0}:stream:orders";
    static final String GROUP = "commerce-order-workers";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = script("lua/commerce_seckill.lua");
    private static final DefaultRedisScript<Long> RECOVER_ADMISSION_SCRIPT =
            script("lua/commerce_recover_admission.lua");
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = script("lua/commerce_compensate.lua");
    private static final DefaultRedisScript<Long> FINALIZE_ORDER_SCRIPT =
            script("lua/commerce_finalize_order.lua");
    private static final DefaultRedisScript<Long> ACK_DELETE_SCRIPT =
            script("lua/commerce_ack_delete.lua");
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
    private final CommerceAdmissionDurabilityService durableAdmissions;
    private final CommerceRedisReplicationExecutor replicatedRedis;
    private final CommerceRedisKeyspace keyspace;
    private final CommerceRuntimeMutationLeases runtimeLeases;
    private final CommerceRoutingGuard routing;
    private final String consumerName;
    private final boolean streamEnabled;
    private final int streamBatchSize;
    private final int streamMaxDeliveries;

    @Autowired
    public CommerceOrderService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            CommerceOrderIdGenerator orderIds,
            CommerceAdmissionDurabilityService durableAdmissions,
            CommerceRedisReplicationExecutor replicatedRedis,
            CommerceRedisKeyspace keyspace,
            CommerceRuntimeMutationLeases runtimeLeases,
            CommerceRoutingGuard routing,
            @Value("${citycopilot.commerce.consumer-name:commerce-local}") String consumerName,
            @Value("${citycopilot.commerce.stream-worker-enabled:${citycopilot.commerce.stream-enabled:true}}")
            boolean streamEnabled,
            @Value("${citycopilot.commerce.stream-batch-size:100}") int streamBatchSize,
            @Value("${citycopilot.commerce.stream-max-deliveries:10}") int streamMaxDeliveries
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.orderIds = orderIds;
        this.durableAdmissions = durableAdmissions;
        this.replicatedRedis = replicatedRedis;
        this.keyspace = keyspace;
        this.runtimeLeases = runtimeLeases;
        this.routing = routing;
        this.consumerName = consumerName + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.streamEnabled = streamEnabled;
        this.streamBatchSize = Math.max(1, streamBatchSize);
        this.streamMaxDeliveries = Math.max(1, streamMaxDeliveries);
    }

    CommerceOrderService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            CommerceOrderIdGenerator orderIds,
            CommerceAdmissionDurabilityService durableAdmissions,
            CommerceRedisReplicationExecutor replicatedRedis,
            String consumerName,
            boolean streamEnabled,
            int streamBatchSize
    ) {
        this(
                jdbc, redis, transactions, objectMapper, orderIds,
                durableAdmissions, replicatedRedis,
                new CommerceRedisKeyspace(1, "0"), null, null,
                consumerName, streamEnabled, streamBatchSize, 10
        );
    }

    @PostConstruct
    void initializeStream() {
        if (!streamEnabled) return;
        for (String stream : keyspace.streams()) {
            RecordId bootstrap = null;
            try {
                bootstrap = redis.opsForStream().add(stream, Map.of("bootstrap", "true"));
                redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), GROUP);
            } catch (RuntimeException failure) {
                if (!isBusyGroup(failure)) {
                    throw failure;
                }
            } finally {
                if (bootstrap != null) {
                    try {
                        redis.opsForStream().delete(stream, bootstrap);
                    } catch (RuntimeException cleanupFailure) {
                        // A leftover bootstrap record is harmless and is ACKed by
                        // the normal worker. Do not hide the initialization error.
                        log.warn("Unable to remove Commerce stream bootstrap record {}", bootstrap);
                    }
                }
            }
        }
    }

    public Map<String, Object> seckill(long voucherId, long userId, String idempotencyKey) {
        requirePositive(voucherId, "voucherId");
        requirePositive(userId, "userId");
        String key = normalizeRequiredIdempotencyKey(idempotencyKey);
        if (runtimeLeases == null) {
            return seckillWithAdmissionLease(voucherId, userId, key, null);
        }
        try (CommerceRuntimeMutationLeases.MutationLease lease =
                     runtimeLeases.acquireAdmission(voucherId)) {
            routing.requireAdmissionActive(voucherId);
            return seckillWithAdmissionLease(voucherId, userId, key, lease);
        }
    }

    private Map<String, Object> seckillWithAdmissionLease(
            long voucherId,
            long userId,
            String key,
            CommerceRuntimeMutationLeases.MutationLease lease
    ) {
        String requestFingerprint = requestFingerprint(voucherId);
        CommerceAdmissionDurabilityService.Admission durable = durableAdmissions.prepare(
                voucherId, userId, key, requestFingerprint, null
        );
        if (durable.wasAccepted()) {
            assertLease(lease);
            return accepted(durable.orderId(), key, "IDEMPOTENT_REPLAY");
        }
        try {
            AdmissionIdentity globalIdentity = reserveGlobalIdempotency(
                    voucherId, userId, key, requestFingerprint, durable.orderId()
            );
            // Always execute the voucher-local Lua, including on replay. Its
            // same-value idempotency write lets the node-bound WAIT confirm
            // this slot rather than trusting a read from another connection.
            Map<String, Object> response = admit(
                    voucherId,
                    userId,
                    globalIdentity.orderId(),
                    key,
                    requestFingerprint
            );
            durableAdmissions.markAccepted(durable);
            assertLease(lease);
            return response;
        } catch (CommerceApiException rejection) {
            persistStableRejection(durable, rejection);
            assertLease(lease);
            throw rejection;
        }
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
        if (runtimeLeases == null) {
            return replayAdmissionWithLease(orderId, voucherId, userId, key, null);
        }
        try (CommerceRuntimeMutationLeases.MutationLease lease =
                     runtimeLeases.acquireAdmission(voucherId)) {
            routing.requireAdmissionActive(voucherId);
            return replayAdmissionWithLease(orderId, voucherId, userId, key, lease);
        }
    }

    private Map<String, Object> replayAdmissionWithLease(
            long orderId,
            long voucherId,
            long userId,
            String key,
            CommerceRuntimeMutationLeases.MutationLease lease
    ) {
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
            assertLease(lease);
            return orderResult(row, "IDEMPOTENT_REPLAY");
        }
        String fingerprint = requestFingerprint(voucherId);
        CommerceAdmissionDurabilityService.Admission durable = durableAdmissions.prepare(
                voucherId, userId, key, fingerprint, orderId
        );
        if (durable.wasAccepted()) {
            assertLease(lease);
            return accepted(durable.orderId(), key, "IDEMPOTENT_REPLAY");
        }
        try {
            AdmissionIdentity globalIdentity = reserveGlobalIdempotency(
                    voucherId, userId, key, fingerprint, orderId
            );
            if (globalIdentity.orderId() != orderId) {
                throw new CommerceApiException(
                        "COMMERCE_REPLAY_IDENTITY_CONFLICT",
                        "Dead-letter order identity conflicts with the global idempotency registry",
                        409
                );
            }
            Map<String, Object> response = admit(voucherId, userId, orderId, key, fingerprint);
            durableAdmissions.markAccepted(durable);
            assertLease(lease);
            return response;
        } catch (CommerceApiException rejection) {
            persistStableRejection(durable, rejection);
            assertLease(lease);
            throw rejection;
        }
    }

    private Map<String, Object> admit(
            long voucherId,
            long userId,
            long orderId,
            String idempotencyKey,
            String requestFingerprint
    ) {
        String admissionKey = admissionKey(voucherId, userId, idempotencyKey);
        Long result;
        try {
            result = replicatedRedis.executeReplicatedScript(
                    "voucher_admission",
                    keyspace.stock(voucherId),
                    SECKILL_SCRIPT,
                    admissionScriptKeys(voucherId, userId, idempotencyKey),
                    String.valueOf(userId),
                    String.valueOf(orderId),
                    String.valueOf(voucherId),
                    idempotencyKey,
                    requestFingerprint,
                    keyspace.activeAdmissionGateValue()
            );
        } catch (RuntimeException error) {
            throw admissionOutcomeUnknown(error);
        }
        if (result == null || result == 5L || result == 7L) {
            throw admissionUnavailable();
        }
        if (result == 1L) {
            throw new CommerceApiException("SECKILL_STOCK_NOT_ENOUGH", "Voucher stock exhausted", 409);
        }
        if (result == 2L) {
            throw new CommerceApiException("SECKILL_DUPLICATE_ORDER", "User already purchased this voucher", 409);
        }
        if (result == 3L) {
            return accepted(orderId, idempotencyKey, "IDEMPOTENT_REPLAY");
        }
        if (result == 4L) {
            throw idempotencyConflict();
        }
        if (result == 6L) {
            throw new CommerceApiException(
                    "COMMERCE_INVENTORY_MAINTENANCE",
                    "Voucher inventory is under maintenance",
                    503
            );
        }
        if (result == 8L) {
            throw new CommerceApiException("VOUCHER_NOT_AVAILABLE", "Voucher is not active", 409);
        }
        if (result == 9L) {
            throw new CommerceApiException("SECKILL_NOT_STARTED", "Seckill has not started", 409);
        }
        if (result == 10L) {
            throw new CommerceApiException("SECKILL_ENDED", "Seckill has ended", 409);
        }
        if (result == 11L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_NOT_ACTIVE",
                    "Commerce order admission is not active for this Redis shard",
                    503
            );
        }
        if (result != 0L) {
            throw admissionUnavailable();
        }
        return accepted(orderId, idempotencyKey, "COMMERCE_ORDER_ACCEPTED");
    }

    private List<String> admissionScriptKeys(
            long voucherId,
            long userId,
            String idempotencyKey
    ) {
        return List.of(
                keyspace.campaignMeta(voucherId),
                keyspace.stock(voucherId),
                keyspace.buyers(voucherId),
                keyspace.stream(voucherId),
                admissionKey(voucherId, userId, idempotencyKey),
                keyspace.fence(voucherId),
                keyspace.inflight(voucherId),
                keyspace.admissionRoutingGate(voucherId)
        );
    }

    private void persistStableRejection(
            CommerceAdmissionDurabilityService.Admission durable,
            CommerceApiException rejection
    ) {
        if (durable.status() != CommerceAdmissionDurabilityService.Status.PREPARED) return;
        if (switch (rejection.code()) {
            case "SECKILL_STOCK_NOT_ENOUGH", "SECKILL_DUPLICATE_ORDER",
                    "COMMERCE_IDEMPOTENCY_CONFLICT", "COMMERCE_REPLAY_IDENTITY_CONFLICT",
                    "VOUCHER_NOT_AVAILABLE", "SECKILL_NOT_STARTED", "SECKILL_ENDED" -> true;
            default -> false;
        }) {
            // If this write fails, markRejected throws a 503 instead of
            // returning a rejection that future retries cannot reproduce.
            durableAdmissions.markRejected(durable, rejection);
        }
    }

    private AdmissionIdentity reserveGlobalIdempotency(
            long voucherId,
            long userId,
            String idempotencyKey,
            String requestFingerprint,
            Long requiredOrderId
    ) {
        String registryKey = keyspace.globalIdempotency(userId, idempotencyKey);
        long candidateOrderId;
        try {
            candidateOrderId = requiredOrderId == null ? orderIds.nextId() : requiredOrderId;
            String stored = replicatedRedis.reserveGlobalIdentity(
                    registryKey,
                    admissionIdentity(candidateOrderId, voucherId, requestFingerprint)
            );
            AdmissionIdentity existing = admissionIdentity(stored);
            if (existing == null) throw admissionUnavailable();
            if (!existing.matches(voucherId, requestFingerprint)) {
                throw idempotencyConflict();
            }
            if (requiredOrderId != null && existing.orderId() != requiredOrderId) {
                throw new CommerceApiException(
                        "COMMERCE_REPLAY_IDENTITY_CONFLICT",
                        "Dead-letter order identity conflicts with the global idempotency registry",
                        409
                );
            }
            return existing;
        } catch (CommerceApiException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw admissionUnavailable();
        }
    }

    @Scheduled(fixedDelayString = "${citycopilot.commerce.stream-poll-ms:50}")
    public void consumeOrders() {
        if (!streamEnabled) return;
        // Redis Cluster cannot XREADGROUP keys from different slots in one
        // command, so each logical hash-bucket stream is polled independently.
        for (String stream : keyspace.streams()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty().count(streamBatchSize),
                        StreamOffset.create(stream, ReadOffset.lastConsumed())
                );
                if (records == null) continue;
                for (MapRecord<String, Object, Object> record : records) {
                    processSafely(record, 1L);
                }
            } catch (Exception unavailable) {
                log.debug("Commerce shard stream {} is temporarily unavailable", stream);
            }
        }
    }

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

    private void reclaimPendingStream(String stream) {
        try {
            Range<String> scanRange = Range.unbounded();
            String previousLastId = null;
            while (true) {
                PendingMessages pending = redis.opsForStream().pending(
                        stream,
                        GROUP,
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
                    List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                            stream,
                            GROUP,
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

    @Scheduled(
            fixedDelayString = "${citycopilot.commerce.admission-recovery-poll-ms:1000}",
            initialDelayString = "${citycopilot.commerce.admission-recovery-poll-ms:1000}"
    )
    public void recoverDurableAdmissions() {
        if (!streamEnabled) return;
        for (CommerceAdmissionDurabilityService.Admission admission
                : durableAdmissions.claimAcceptedBatch()) {
            recoverDurableAdmission(admission);
        }
    }

    private void recoverDurableAdmission(
            CommerceAdmissionDurabilityService.Admission admission
    ) {
        try {
            if (durableOrderExists(admission)) {
                durableAdmissions.markOrderCreated(admission);
                return;
            }
            AdmissionIdentity global = reserveGlobalIdempotency(
                    admission.voucherId(),
                    admission.userId(),
                    admission.idempotencyKey(),
                    admission.requestFingerprint(),
                    admission.orderId()
            );
            if (global.orderId() != admission.orderId()) {
                throw new IllegalStateException("Durable admission global identity conflicts");
            }
            Long result = replicatedRedis.executeReplicatedScript(
                    "voucher_admission_recovery",
                    keyspace.stock(admission.voucherId()),
                    RECOVER_ADMISSION_SCRIPT,
                    admissionScriptKeys(
                            admission.voucherId(),
                            admission.userId(),
                            admission.idempotencyKey()
                    ),
                    String.valueOf(admission.userId()),
                    String.valueOf(admission.orderId()),
                    String.valueOf(admission.voucherId()),
                    admission.idempotencyKey(),
                    admission.requestFingerprint(),
                    keyspace.activeAdmissionGateValue(),
                    keyspace.drainingAdmissionGateValue()
            );
            if (result == null || result != 0L && result != 3L) {
                throw new IllegalStateException(
                        "Redis durable admission recovery returned " + result
                );
            }
            durableAdmissions.releaseAfterQueueConfirmation(admission);
        } catch (RuntimeException failure) {
            try {
                durableAdmissions.releaseForRetryOrRepair(admission, failure);
            } catch (RuntimeException ledgerFailure) {
                log.error(
                        "Unable to record failed durable admission recovery for order {}",
                        admission.orderId(),
                        ledgerFailure
                );
            }
        }
    }

    private boolean durableOrderExists(
            CommerceAdmissionDurabilityService.Admission admission
    ) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT id,user_id,voucher_id,idempotency_key
                FROM commerce_order
                WHERE id=? OR (user_id=? AND idempotency_key=?)
                LIMIT 2
                """, admission.orderId(), admission.userId(), admission.idempotencyKey());
        if (orders.isEmpty()) return false;
        if (orders.size() != 1) {
            throw new IllegalStateException("Durable admission matches multiple Commerce orders");
        }
        Map<String, Object> order = orders.get(0);
        boolean identical = ((Number) order.get("id")).longValue() == admission.orderId()
                && ((Number) order.get("user_id")).longValue() == admission.userId()
                && ((Number) order.get("voucher_id")).longValue() == admission.voucherId()
                && admission.idempotencyKey().equals(String.valueOf(order.get("idempotency_key")));
        if (!identical) {
            throw new IllegalStateException("Durable admission conflicts with an existing Commerce order");
        }
        return true;
    }

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

    private Long acknowledgeAndDelete(String stream, String recordId) {
        return redis.execute(
                ACK_DELETE_SCRIPT,
                List.of(stream),
                GROUP,
                recordId
        );
    }

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
            durableAdmissions.markOrderCreated(orderId, voucherId, userId, idempotencyKey);
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
        // Commit the DB ownership check before any Redis call. If the process
        // dies after the Lua marker is written, the same phase can replay the
        // compensation without holding a database transaction open.
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
        transactions.executeWithoutResult(status -> {
            requireOwnedPhase(sourceId, claimToken, PHASE_COMPENSATION_REQUIRED);
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
        if (runtimeLeases == null) {
            return transitionWithLease(orderId, userId, action, payType, admin, null);
        }
        try (CommerceRuntimeMutationLeases.MutationLease lease = runtimeLeases.acquireMutation()) {
            routing.requireRuntimeMutationActive();
            Map<String, Object> result = transitionWithLease(
                    orderId, userId, action, payType, admin, lease
            );
            lease.renewAndAssert();
            return result;
        }
    }

    private Map<String, Object> transitionWithLease(
            long orderId,
            long userId,
            String action,
            Integer payType,
            boolean admin,
            CommerceRuntimeMutationLeases.MutationLease lease
    ) {
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
                assertLease(lease);
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
                // TransactionTemplate commits only after this callback returns.
                // A lost routing lease therefore rolls back every SQL mutation.
                assertLease(lease);
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
                        fenceToken,
                        lease
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
        if (!streamEnabled) return;
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
            try (CommerceRuntimeMutationLeases.MutationLease lease =
                         runtimeLeases == null ? null : runtimeLeases.acquireRecoveryMutation()) {
                attemptTransitionInventoryRestore(
                        String.valueOf(row.get("source_id")),
                        ((Number) row.get("order_id")).longValue(),
                        ((Number) row.get("voucher_id")).longValue(),
                        String.valueOf(row.get("inventory_fence_token")),
                        lease
                );
                assertLease(lease);
            } catch (Exception failure) {
                log.warn("Unable to resume inventory restore for {}", row.get("source_id"));
            }
        }
    }

    private void assertLease(CommerceRuntimeMutationLeases.MutationLease lease) {
        if (lease != null) lease.renewAndAssert();
    }

    String attemptTransitionInventoryRestore(
            String sourceId,
            long orderId,
            long voucherId,
            String fenceToken
    ) {
        return attemptTransitionInventoryRestore(
                sourceId, orderId, voucherId, fenceToken, null
        );
    }

    private String attemptTransitionInventoryRestore(
            String sourceId,
            long orderId,
            long voucherId,
            String fenceToken,
            CommerceRuntimeMutationLeases.MutationLease lease
    ) {
        Long restored;
        try {
            assertLease(lease);
            restored = redis.execute(
                    RESTORE_INVENTORY_SCRIPT,
                    List.of(
                            keyspace.fence(voucherId),
                            keyspace.stock(voucherId),
                            keyspace.restoreMarker(voucherId, sourceId)
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
            assertLease(lease);
            int completed = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase=?, compensation_delta=0, terminal_at=CURRENT_TIMESTAMP(3)
                    WHERE source_id=? AND phase=?
                    """, PHASE_RESTORED, sourceId, PHASE_RESTORE_PENDING);
            if (completed == 1) {
                try {
                    redis.delete(keyspace.restoreMarker(voucherId, sourceId));
                } catch (Exception cleanupFailure) {
                    log.debug("Unable to remove completed inventory restore marker {}", sourceId);
                }
            }
            assertLease(lease);
            return PHASE_RESTORED;
        }
        String reason = restored != null && restored == -2L
                 ? "redis_stock_missing_during_transition_restore"
                : "inventory_fence_expired_before_transition_restore";
        assertLease(lease);
        jdbc.update("""
                UPDATE commerce_order_recovery
                SET phase=?, compensation_delta=1, last_error=?,
                    terminal_at=CURRENT_TIMESTAMP(3)
                WHERE source_id=? AND phase=?
                """, PHASE_REPAIR_REQUIRED, reason, sourceId, PHASE_RESTORE_PENDING);
        assertLease(lease);
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
                        keyspace.stock(voucherId),
                        keyspace.buyers(voucherId),
                        admissionKey(voucherId, userId, idempotencyKey),
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

    private String acquireInventoryFence(long voucherId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(
                keyspace.fence(voucherId),
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
                    List.of(keyspace.fence(voucherId)),
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

    private String admissionKey(long voucherId, long userId, String idempotencyKey) {
        return keyspace.idempotency(voucherId, userId, idempotencyKey);
    }

    static String inflightMember(String sourceId, long orderId) {
        return sourceId + "|" + orderId;
    }

    private AdmissionIdentity admissionIdentity(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split("\\|", 4);
        if (parts.length < 2) {
            // Old numeric-only admissions cannot prove voucher identity and
            // must not be treated as a replay for an arbitrary voucher.
            return null;
        }
        try {
            return new AdmissionIdentity(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    parts.length >= 3 ? parts[2] : null,
                    parts.length == 4 ? parts[3] : null
            );
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private String admissionIdentity(
            long orderId,
            long voucherId,
            String requestFingerprint
    ) {
        return orderId + "|" + voucherId + "|" + requestFingerprint;
    }

    private String requestFingerprint(long voucherId) {
        return "seckill:v1:" + voucherId;
    }

    private CommerceApiException admissionUnavailable() {
        return new CommerceApiException(
                "COMMERCE_REDIS_UNAVAILABLE",
                "Seckill admission unavailable",
                503
        );
    }

    private CommerceApiException admissionOutcomeUnknown(RuntimeException cause) {
        CommerceApiException error = new CommerceApiException(
                "COMMERCE_ADMISSION_OUTCOME_UNKNOWN",
                "Admission outcome is unknown; retry with the same Idempotency-Key",
                503
        );
        error.initCause(cause);
        return error;
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

    private String normalizeRequiredIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new CommerceApiException(
                    "COMMERCE_IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required",
                    400
            );
        }
        return normalizeIdempotencyKey(value, "");
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

    private String recordStream(MapRecord<String, Object, Object> record) {
        String stream = record.getStream();
        if (stream != null) return stream;
        if (keyspace.streams().size() == 1) return keyspace.streams().get(0);
        throw new IllegalArgumentException("Stream record is missing its logical shard key");
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

    private record AdmissionIdentity(
            long orderId,
            long voucherId,
            String requestFingerprint,
            String sourceId
    ) {
        private AdmissionIdentity(long orderId, long voucherId, String requestFingerprint) {
            this(orderId, voucherId, requestFingerprint, null);
        }

        private boolean matches(long expectedVoucherId, String expectedFingerprint) {
            return voucherId == expectedVoucherId
                    && (requestFingerprint == null || requestFingerprint.equals(expectedFingerprint));
        }
    }

    private record PoisonIdentity(
            long orderId,
            long voucherId,
            long userId,
            String idempotencyKey
    ) {}

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

    private static final class LostRecoveryClaim extends RuntimeException {
    }
}
