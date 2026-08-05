package com.citycopilot.commerce;

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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable source of truth for synchronous Commerce admission decisions.
 *
 * <p>The Redis global idempotency key and the voucher-local Lua/Stream write
 * live in different Cluster slots. A connection-independent {@code WAIT}
 * therefore cannot prove that both writes were replicated by their respective
 * primaries. This ledger records the identity before Redis admission and only
 * records {@link Status#ACCEPTED} after Redis proves the voucher-local write.
 * The API must not return an accepted response until that transition commits.</p>
 *
 * <p>{@link Status#PREPARED} rows are intentionally never scanned by the
 * background recovery worker. A client retry may finish the same Redis
 * admission with the reserved order ID, but the worker may recover only rows
 * that the request path already proved were accepted.</p>
 */
@Component
final class CommerceAdmissionDurabilityService {
    private static final Logger log = LoggerFactory.getLogger(CommerceAdmissionDurabilityService.class);
    private static final String METRIC_DECISION = "citycopilot.commerce.admission.durability";
    private static final String METRIC_RECOVERY = "citycopilot.commerce.admission.recovery";
    private static final String LEGACY_ORDER_ROUTING_GENERATION = "legacy-order-v0";

    private final JdbcTemplate jdbc;
    private final CommerceOrderIdGenerator orderIds;
    private final CommerceRoutingGuard routingGuard;
    private final String routingGeneration;
    private final int routingShardCount;
    private final MeterRegistry metrics;
    private final int recoveryBatchSize;
    private final long recoveryMinAgeMicros;
    private final long recoveryLeaseMicros;
    private final long recoveryRecheckMicros;
    private final int recoveryMaxAttempts;
    private final long maxAcceptedBacklog;
    private final long maxAcceptedAgeMillis;
    private final long capacityCacheNanos;
    private final String claimOwner;
    private final AtomicLong acceptedBacklog = new AtomicLong();
    private final AtomicLong oldestAcceptedAgeMillis = new AtomicLong();
    private final AtomicLong repairRequired = new AtomicLong();
    private volatile long capacityObservedAtNanos;

    @Autowired
    CommerceAdmissionDurabilityService(
            JdbcTemplate jdbc,
            CommerceOrderIdGenerator orderIds,
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard routingGuard,
            MeterRegistry metrics,
            @Value("${citycopilot.commerce.admission-recovery-batch-size:100}") int recoveryBatchSize,
            @Value("${citycopilot.commerce.admission-recovery-min-age-ms:5000}") long recoveryMinAgeMillis,
            @Value("${citycopilot.commerce.admission-recovery-lease-ms:30000}") long recoveryLeaseMillis,
            @Value("${citycopilot.commerce.admission-recovery-recheck-ms:5000}") long recoveryRecheckMillis,
            @Value("${citycopilot.commerce.admission-recovery-max-attempts:20}") int recoveryMaxAttempts,
            @Value("${citycopilot.commerce.admission-max-backlog:50000}") long maxAcceptedBacklog,
            @Value("${citycopilot.commerce.admission-max-oldest-age-ms:300000}") long maxAcceptedAgeMillis,
            @Value("${citycopilot.commerce.admission-capacity-cache-ms:1000}") long capacityCacheMillis,
            @Value("${citycopilot.commerce.consumer-name:commerce-local}") String nodeName
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
        this.orderIds = orderIds;
        this.routingGuard = routingGuard;
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
            Admission legacyOrder = materializeLegacyOrderIfPresent(
                    voucherId,
                    userId,
                    idempotencyKey,
                    requestFingerprint,
                    requiredOrderId
            );
            if (legacyOrder != null) {
                outcome = "legacy_order_replay";
                return legacyOrder;
            }
            assertRecoveryCapacity();
            long orderId = requiredOrderId == null ? orderIds.nextId() : requiredOrderId;
            jdbc.update("""
                    INSERT IGNORE INTO commerce_admission_ledger(
                        order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
                        routing_generation,physical_shard_count,status
                    ) VALUES (?,?,?,?,?,?,?,'PREPARED')
                    """, orderId, userId, idempotencyKey, voucherId, requestFingerprint,
                    routingGeneration, routingShardCount);
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
            if (!"failure".equals(outcome)) {
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

    void markAccepted(Admission admission) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            int changed = jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET status='ACCEPTED',accepted_at=COALESCE(accepted_at,CURRENT_TIMESTAMP(3)),
                        rejection_code=NULL,rejection_http_status=NULL,rejection_message=NULL,
                        last_error=NULL
                    WHERE order_id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                      AND request_fingerprint=?
                      AND routing_generation=? AND physical_shard_count=?
                      AND status='PREPARED'
                    """, admission.orderId(), admission.userId(), admission.voucherId(),
                    admission.idempotencyKey(), admission.requestFingerprint(),
                    admission.routingGeneration(), admission.routingShardCount());
            if (changed == 0) {
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

    List<Admission> claimAcceptedBatch() {
        String token = UUID.randomUUID().toString();
        try {
            int claimed = jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET claim_token=?,claim_owner=?,
                        claimed_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3))
                    WHERE status='ACCEPTED'
                      AND routing_generation=? AND physical_shard_count=?
                      AND accepted_at<=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3))
                      AND (claimed_until IS NULL OR claimed_until<CURRENT_TIMESTAMP(3))
                    ORDER BY accepted_at,order_id
                    LIMIT ?
                    """, token, claimOwner, recoveryLeaseMicros,
                    routingGeneration, routingShardCount, -recoveryMinAgeMicros, recoveryBatchSize);
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
     * Explicit operator recovery transition. It never admits a PREPARED row;
     * it only makes a previously accepted, terminally alerted identity
     * eligible for the same claimed MySQL recovery path again.
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
        if (current.status() == Status.PREPARED
                && !current.matchesRouting(routingGeneration, routingShardCount)) {
            current = rebindPreparedToActiveRouting(current);
        }
        return switch (current.status()) {
            case PREPARED -> {
                if (!current.matchesRouting(routingGeneration, routingShardCount)) {
                    throw new CommerceApiException(
                            "COMMERCE_ADMISSION_ROUTING_CONFLICT",
                            "Prepared Commerce admission could not be rebound to the active Redis routing generation",
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

    /**
     * Moves only an unaccepted identity to the routing generation currently
     * owned by this replica. The Redis control and shard gates are checked on
     * both sides of the MySQL compare-and-set. A concurrent acceptance or
     * terminal transition therefore wins without ever changing its routing
     * ownership.
     */
    private Admission rebindPreparedToActiveRouting(Admission prepared) {
        routingGuard.requireAdmissionActive(prepared.voucherId());
        int changed = jdbc.update("""
                UPDATE commerce_admission_ledger
                SET routing_generation=?,physical_shard_count=?,updated_at=CURRENT_TIMESTAMP(3)
                WHERE order_id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                  AND request_fingerprint=? AND status='PREPARED'
                  AND routing_generation=? AND physical_shard_count=?
                """, routingGeneration, routingShardCount,
                prepared.orderId(), prepared.userId(), prepared.voucherId(),
                prepared.idempotencyKey(), prepared.requestFingerprint(),
                prepared.routingGeneration(), prepared.routingShardCount());

        Admission current = find(prepared.userId(), prepared.idempotencyKey());
        if (current == null || !current.sameOrderIdentity(
                prepared.orderId(),
                prepared.voucherId(),
                prepared.userId(),
                prepared.idempotencyKey(),
                prepared.requestFingerprint()
        )) {
            throw durabilityUnavailable(
                    "Prepared Commerce admission changed identity during routing rebind",
                    null
            );
        }
        if (changed == 1) {
            routingGuard.requireAdmissionActive(prepared.voucherId());
            if (!current.matchesRouting(routingGeneration, routingShardCount)) {
                throw durabilityUnavailable(
                        "Prepared Commerce admission routing rebind was not durable",
                        null
                );
            }
            count(METRIC_DECISION, "prepared_rebound");
            return current;
        }
        if (changed != 0) {
            throw durabilityUnavailable(
                    "Prepared Commerce admission routing rebind affected multiple rows",
                    null
            );
        }

        // A concurrent request may already have rebound the PREPARED row, or
        // completed the original routing's admission. In either case, return
        // the reread state without moving ACCEPTED or terminal ownership.
        return current;
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
            // A concurrent ledger materialization is accepted only after the
            // exact MySQL order identity is re-read below.
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
                "COMMERCE_DURABLE_ADMISSION_UNAVAILABLE",
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

    enum Status {
        PREPARED,
        ACCEPTED,
        REJECTED,
        ORDER_CREATED,
        REPAIR_REQUIRED
    }

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
