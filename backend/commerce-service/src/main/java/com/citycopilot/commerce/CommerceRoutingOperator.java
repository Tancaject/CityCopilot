package com.citycopilot.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One-shot production operator for the Commerce routing control plane.
 *
 * <p>The one-shot process runs the ordinary Commerce artifact with every runtime
 * role disabled. Redis control mutations are delegated to
 * {@link CommerceRedisReplicationExecutor}; each Lua CAS/SET and WAIT uses the
 * same physical master connection and therefore remains valid with Redis
 * Cluster, TLS and ACL authentication configured by Spring/Lettuce.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.commerce.routing-operator",
        name = "enabled",
        havingValue = "true"
)
final class CommerceRoutingOperator implements ApplicationRunner {
    private static final int REQUIRED_COMMERCE_TABLES = 11;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final CommerceRedisReplicationExecutor replicatedRedis;
    private final CommerceRuntimeMutationLeases runtimeLeases;
    private final CommerceRedisKeyspace source;
    private final ObjectMapper objectMapper;
    private final Action action;
    private final String targetGeneration;
    private final int targetPhysicalShardCount;
    private volatile boolean completed;
    private volatile CommerceRuntimeMutationLeases.OperatorLease activeOperatorLease;

    CommerceRoutingOperator(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            CommerceRedisReplicationExecutor replicatedRedis,
            CommerceRuntimeMutationLeases runtimeLeases,
            CommerceRedisKeyspace source,
            ObjectMapper objectMapper,
            @Value("${citycopilot.commerce.routing-operator.action:status}") String action,
            @Value("${citycopilot.commerce.routing-operator.target-generation:}")
            String targetGeneration,
            @Value("${citycopilot.commerce.routing-operator.target-physical-shard-count:0}")
            int targetPhysicalShardCount
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.replicatedRedis = replicatedRedis;
        this.runtimeLeases = runtimeLeases;
        this.source = source;
        this.objectMapper = objectMapper;
        this.action = Action.parse(action);
        this.targetGeneration = targetGeneration == null ? "" : targetGeneration.trim();
        this.targetPhysicalShardCount = targetPhysicalShardCount;
    }

    @Override
    public void run(ApplicationArguments ignored) throws Exception {
        try {
            Map<String, Object> result = execute();
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            completed = true;
        } catch (RoutingPreflightException failed) {
            System.err.println(
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(failed.evidence())
            );
            throw failed;
        }
    }

    boolean completed() {
        return completed;
    }

    Map<String, Object> execute() {
        if (action == Action.STATUS) return status();
        if (action == Action.VERIFY_DRAIN) return verifyDrain();
        try (CommerceRuntimeMutationLeases.OperatorLease lease = runtimeLeases.acquireOperator()) {
            activeOperatorLease = lease;
            lease.renewAndAssert();
            try {
                return switch (action) {
                    case INITIALIZE -> initialize();
                    case BEGIN_DRAIN -> beginDrain();
                    case ACTIVATE -> activate();
                    default -> throw new IllegalStateException("Unexpected routing operator action");
                };
            } finally {
                activeOperatorLease = null;
            }
        }
    }

    private Map<String, Object> status() {
        CommerceRoutingGuard.RoutingSnapshot current = readControl();
        Map<String, Object> result = baseResult("status");
        result.put("control", describe(current));
        result.put("sourceGates", gateSnapshot(source));
        return result;
    }

    private Map<String, Object> initialize() {
        CommerceRoutingGuard.RoutingSnapshot current = readControl();
        if (current == null) {
            initializeGates(source, CommerceRoutingGuard.RoutingState.ACTIVE);
            String value = source.routingControlValue("ACTIVE", revision());
            assertOperatorLease();
            requireMutation(
                    "routing_control_initialize",
                    CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                    replicatedRedis.setIfAbsent(
                            "routing_control_initialize",
                            CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                            value
                    )
            );
            current = readControl();
        }
        assertFingerprint(current, source);
        if (current.state() != CommerceRoutingGuard.RoutingState.ACTIVE) {
            throw new IllegalStateException("Existing Commerce routing control is not ACTIVE");
        }
        confirmControlReplication(current, "routing_control_confirm_initialize");
        initializeGates(source, CommerceRoutingGuard.RoutingState.ACTIVE);
        assertGates(source, CommerceRoutingGuard.RoutingState.ACTIVE);

        Map<String, Object> result = baseResult("initialize");
        result.put("control", describe(current));
        result.put("safe", true);
        return result;
    }

    private Map<String, Object> beginDrain() {
        CommerceRoutingGuard.RoutingSnapshot current = requireControl();
        assertFingerprint(current, source);
        if (current.state() == CommerceRoutingGuard.RoutingState.ACTIVE) {
            String expected = controlValue(current);
            String next = source.routingControlValue("DRAINING", revision());
            assertOperatorLease();
            requireMutation(
                    "routing_control_begin_drain",
                    CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                    replicatedRedis.compareAndSet(
                            "routing_control_begin_drain",
                            CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                            expected,
                            next
                    )
            );
            current = requireControl();
        }
        assertFingerprint(current, source);
        if (current.state() != CommerceRoutingGuard.RoutingState.DRAINING) {
            throw new IllegalStateException("Commerce routing control cannot enter DRAINING");
        }
        confirmControlReplication(current, "routing_control_confirm_drain");
        transitionGates(
                source,
                CommerceRoutingGuard.RoutingState.ACTIVE,
                CommerceRoutingGuard.RoutingState.DRAINING,
                "routing_gate_begin_drain"
        );
        assertGates(source, CommerceRoutingGuard.RoutingState.DRAINING);

        Map<String, Object> result = baseResult("begin-drain");
        result.put("control", describe(current));
        result.put("admissionClosed", true);
        return result;
    }

    private Map<String, Object> verifyDrain() {
        CommerceRoutingGuard.RoutingSnapshot current = requireSourceDraining();
        Map<String, Object> drain = drainSnapshot(source);
        Map<String, Object> result = baseResult("verify-drain");
        result.put("control", describe(current));
        result.put("drain", drain);
        if (!Boolean.TRUE.equals(drain.get("safe"))) {
            throw new RoutingPreflightException("Commerce runtime is not drained", result);
        }
        return result;
    }

    private Map<String, Object> activate() {
        CommerceRedisKeyspace target = targetKeyspace();
        CommerceRoutingGuard.RoutingSnapshot current = requireControl();
        boolean fingerprintChanged = !sameFingerprint(source, target);
        if (current.isActiveFor(target)
                && allGatesInState(target, CommerceRoutingGuard.RoutingState.ACTIVE)) {
            confirmControlReplication(current, "routing_control_confirm_already_active");
            Map<String, Object> result = baseResult("activate");
            result.put("control", describe(current));
            result.put("target", fingerprint(target));
            result.put("activated", true);
            result.put("alreadyActivated", true);
            return result;
        }
        boolean targetCommitted = current.isActiveFor(target)
                && (fingerprintChanged || anyGateInState(target, CommerceRoutingGuard.RoutingState.DRAINING));

        Map<String, Object> drain = null;
        Map<String, Object> preCasPreflight = null;
        Map<String, Object> preflight = null;
        Map<String, Object> preparedMigration;
        if (!targetCommitted) {
            assertFingerprint(current, source);
            if (current.state() != CommerceRoutingGuard.RoutingState.DRAINING) {
                throw new IllegalStateException("BeginDrain must complete before Activate");
            }
            assertGates(source, CommerceRoutingGuard.RoutingState.DRAINING);
            drain = drainSnapshot(source);
            if (!Boolean.TRUE.equals(drain.get("safe"))) {
                Map<String, Object> evidence = baseResult("activate");
                evidence.put("control", describe(current));
                evidence.put("drain", drain);
                throw new RoutingPreflightException("Commerce runtime is not drained", evidence);
            }
            preCasPreflight = targetPreflight(target);
            if (!Boolean.TRUE.equals(preCasPreflight.get("safe"))) {
                Map<String, Object> evidence = baseResult("activate");
                evidence.put("control", describe(current));
                evidence.put("targetPreflight", preCasPreflight);
                throw new RoutingPreflightException(
                        "Target Commerce routing keyspace failed business-data preflight",
                        evidence
                );
            }
            initializeGates(target, CommerceRoutingGuard.RoutingState.DRAINING);
            assertGates(target, CommerceRoutingGuard.RoutingState.DRAINING);
            String next = target.routingControlValue("ACTIVE", revision());
            assertOperatorLease();
            requireMutation(
                    "routing_control_activate",
                    CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                    replicatedRedis.compareAndSet(
                            "routing_control_activate",
                            CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                            controlValue(current),
                            next
                    )
            );
            current = requireControl();
            assertFingerprint(current, target);
            // Only the CAS winner may change PREPARED routing ownership.
            preparedMigration = migratePreparedAdmissions(source, target);
            preflight = targetPreflight(target);
            if (!Boolean.TRUE.equals(preflight.get("safe"))) {
                Map<String, Object> evidence = baseResult("activate-post-cas");
                evidence.put("control", describe(current));
                evidence.put("targetPreflight", preflight);
                throw new RoutingPreflightException(
                        "Target Commerce routing keyspace changed after control CAS",
                        evidence
                );
            }
        } else {
            if (fingerprintChanged) {
                assertGates(source, CommerceRoutingGuard.RoutingState.DRAINING);
            }
            // A crash while reopening target shards can leave mixed gates.
            // Close every target shard again, wait for any admission that won
            // before its gate closed, and only then resume the MySQL move.
            transitionGates(
                    target,
                    CommerceRoutingGuard.RoutingState.ACTIVE,
                    CommerceRoutingGuard.RoutingState.DRAINING,
                    "routing_gate_close_activate_resume"
            );
            assertGates(target, CommerceRoutingGuard.RoutingState.DRAINING);
            // A previous write may have reached the master but failed WAIT.
            // Reaffirm the committed value before performing any MySQL move.
            confirmControlReplication(current, "routing_control_confirm_activate_resume");
            preparedMigration = migratePreparedAdmissions(source, target);
            // A crash can leave the global CAS committed while every target
            // gate remains fail-closed. Re-validate business data on every
            // resume; evidence collected before the crash may already be stale.
            preflight = targetPreflight(target);
            if (!Boolean.TRUE.equals(preflight.get("safe"))) {
                Map<String, Object> evidence = baseResult("activate-resume");
                evidence.put("control", describe(current));
                evidence.put("targetPreflight", preflight);
                throw new RoutingPreflightException(
                        "Target Commerce routing keyspace failed resume preflight",
                        evidence
                );
            }
        }

        Map<String, Object> postCasDrain = drainSnapshot(source);
        if (!Boolean.TRUE.equals(postCasDrain.get("safe"))) {
            Map<String, Object> evidence = baseResult("activate-post-cas-drain");
            evidence.put("control", describe(current));
            evidence.put("drain", postCasDrain);
            throw new RoutingPreflightException(
                    "Source Commerce runtime changed after control CAS",
                    evidence
            );
        }
        // The final PREPARED move and target check both happen while the
        // operator lease blocks runtime business writers and before any target
        // shard gate opens.
        Map<String, Object> preparedPostflight = migratePreparedAdmissions(source, target);
        preflight = targetPreflight(target);
        if (!Boolean.TRUE.equals(preflight.get("safe"))) {
            Map<String, Object> evidence = baseResult("activate-final-preflight");
            evidence.put("control", describe(current));
            evidence.put("targetPreflight", preflight);
            throw new RoutingPreflightException(
                    "Target Commerce routing keyspace failed final preflight",
                    evidence
            );
        }
        confirmControlReplication(current, "routing_control_confirm_activate");
        transitionGates(
                target,
                CommerceRoutingGuard.RoutingState.DRAINING,
                CommerceRoutingGuard.RoutingState.ACTIVE,
                "routing_gate_activate"
        );
        assertGates(target, CommerceRoutingGuard.RoutingState.ACTIVE);

        Map<String, Object> result = baseResult("activate");
        result.put("control", describe(current));
        result.put("source", fingerprint(source));
        result.put("target", fingerprint(target));
        result.put("activated", true);
        result.put("resumedAfterCommittedCas", targetCommitted);
        if (drain != null) result.put("drain", drain);
        result.put("postCasDrain", postCasDrain);
        if (preCasPreflight != null) result.put("targetPreflightBeforeCas", preCasPreflight);
        result.put("targetPreflight", preflight);
        result.put("preparedMigration", preparedMigration);
        result.put("preparedPostflight", preparedPostflight);
        return result;
    }

    private Map<String, Object> migratePreparedAdmissions(
            CommerceRedisKeyspace sourceKeyspace,
            CommerceRedisKeyspace targetKeyspace
    ) {
        assertOperatorLease();
        boolean changedFingerprint = !sameFingerprint(sourceKeyspace, targetKeyspace);
        int migrated = 0;
        if (changedFingerprint) {
            migrated = jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET routing_generation=?,physical_shard_count=?,
                        updated_at=CURRENT_TIMESTAMP(3)
                    WHERE routing_generation=? AND physical_shard_count=?
                      AND status='PREPARED'
                    """,
                    targetKeyspace.routingGeneration(),
                    targetKeyspace.physicalShardCount(),
                    sourceKeyspace.routingGeneration(),
                    sourceKeyspace.physicalShardCount()
            );
        }
        long sourceRemaining = changedFingerprint ? count("""
                SELECT COUNT(*) FROM commerce_admission_ledger
                WHERE routing_generation=? AND physical_shard_count=? AND status='PREPARED'
                """,
                sourceKeyspace.routingGeneration(),
                sourceKeyspace.physicalShardCount()
        ) : 0L;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("migrated", migrated);
        result.put("sourcePreparedRemaining", sourceRemaining);
        result.put("target", fingerprint(targetKeyspace));
        result.put("safe", sourceRemaining == 0L);
        if (sourceRemaining != 0L) {
            throw new RoutingPreflightException(
                    "Source PREPARED admissions appeared during routing activation",
                    result
            );
        }
        return result;
    }

    private CommerceRoutingGuard.RoutingSnapshot requireSourceDraining() {
        CommerceRoutingGuard.RoutingSnapshot current = requireControl();
        assertFingerprint(current, source);
        if (current.state() != CommerceRoutingGuard.RoutingState.DRAINING) {
            throw new IllegalStateException("BeginDrain must complete before VerifyDrain");
        }
        assertGates(source, CommerceRoutingGuard.RoutingState.DRAINING);
        return current;
    }

    private void initializeGates(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        String value = gateValue(keyspace, state);
        for (String gate : keyspace.admissionRoutingGates()) {
            assertOperatorLease();
            CommerceRedisReplicationExecutor.ReplicatedMutation mutation =
                    replicatedRedis.setIfAbsent("routing_gate_initialize", gate, value);
            requireMutation("routing_gate_initialize", gate, mutation);
        }
    }

    private void transitionGates(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState expectedState,
            CommerceRoutingGuard.RoutingState nextState,
            String operation
    ) {
        String expected = gateValue(keyspace, expectedState);
        String next = gateValue(keyspace, nextState);
        for (String gate : keyspace.admissionRoutingGates()) {
            assertOperatorLease();
            requireMutation(
                    operation,
                    gate,
                    replicatedRedis.compareAndSet(operation, gate, expected, next)
            );
        }
    }

    private void requireMutation(
            String operation,
            String key,
            CommerceRedisReplicationExecutor.ReplicatedMutation mutation
    ) {
        if (mutation == CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT) {
            throw new IllegalStateException(
                    "Concurrent or conflicting Redis routing mutation: operation="
                            + operation + ", key=" + key
            );
        }
    }

    private void confirmControlReplication(
            CommerceRoutingGuard.RoutingSnapshot current,
            String operation
    ) {
        assertOperatorLease();
        String value = controlValue(current);
        requireMutation(
                operation,
                CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                replicatedRedis.compareAndSet(
                        operation,
                        CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                        value,
                        value
                )
        );
    }

    private void assertOperatorLease() {
        CommerceRuntimeMutationLeases.OperatorLease lease = activeOperatorLease;
        if (lease == null) {
            throw new IllegalStateException("Commerce routing operator lease is not held");
        }
        lease.renewAndAssert();
    }

    private void assertGates(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        String expected = gateValue(keyspace, state);
        for (String gate : keyspace.admissionRoutingGates()) {
            String current = redis.opsForValue().get(gate);
            if (!expected.equals(current)) {
                throw new IllegalStateException(
                        "Commerce routing gate mismatch: key=" + gate
                                + ", expected=" + expected + ", actual=" + current
                );
            }
        }
    }

    private boolean anyGateInState(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        String expected = gateValue(keyspace, state);
        for (String gate : keyspace.admissionRoutingGates()) {
            if (expected.equals(redis.opsForValue().get(gate))) return true;
        }
        return false;
    }

    private boolean allGatesInState(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        String expected = gateValue(keyspace, state);
        for (String gate : keyspace.admissionRoutingGates()) {
            if (!expected.equals(redis.opsForValue().get(gate))) return false;
        }
        return true;
    }

    private Map<String, Object> gateSnapshot(CommerceRedisKeyspace keyspace) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.putAll(fingerprint(keyspace));
        List<Map<String, Object>> gates = new ArrayList<>();
        for (int shard = 0; shard < keyspace.admissionRoutingGates().size(); shard++) {
            String key = keyspace.admissionRoutingGates().get(shard);
            gates.add(Map.of(
                    "shard", shard,
                    "key", key,
                    "value", Objects.toString(redis.opsForValue().get(key), "")
            ));
        }
        snapshot.put("gates", gates);
        return snapshot;
    }

    private Map<String, Object> drainSnapshot(CommerceRedisKeyspace keyspace) {
        Map<String, Object> integrity = integritySnapshot();
        Map<String, Long> ledger = ledgerSnapshot(keyspace);
        List<Long> voucherIds = voucherIds();
        Map<String, Object> redisState = redisDrainState(keyspace, voucherIds);
        long activeRuntimeMutations = runtimeLeases.activeMutationCount();
        long activeAdmissionMutations = runtimeLeases.activeAdmissionCount(keyspace);
        long metadataUpdateFences = metadataUpdateFenceCount(keyspace, voucherIds);

        long unpublishedOutbox = count("""
                SELECT COUNT(*) FROM commerce_outbox_event
                WHERE status <> 'PUBLISHED'
                """);
        long inFlightReservations = count("""
                SELECT COUNT(*) FROM commerce_reservation
                WHERE status IN ('RESERVED','RELEASE_REQUIRED')
                """);
        long unreplayedDeadLetters = count("""
                SELECT COUNT(*) FROM commerce_dead_letter WHERE replayed_at IS NULL
                """);
        long unresolvedRecovery = count("""
                SELECT COUNT(*) FROM commerce_order_recovery
                WHERE phase IS NULL
                   OR phase NOT IN ('ORDER_CREATED','DEAD_LETTERED','RESTORED')
                """);

        boolean safe = Boolean.TRUE.equals(integrity.get("safe"))
                && unpublishedOutbox == 0
                && inFlightReservations == 0
                && unreplayedDeadLetters == 0
                && unresolvedRecovery == 0
                && ledger.get("accepted") == 0
                && ledger.get("repairRequired") == 0
                && ledger.get("activeClaims") == 0
                && activeRuntimeMutations == 0
                && activeAdmissionMutations == 0
                && metadataUpdateFences == 0
                && Boolean.TRUE.equals(redisState.get("drained"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(fingerprint(keyspace));
        result.put("integrity", integrity);
        result.put("unpublishedOutbox", unpublishedOutbox);
        result.put("inFlightReservations", inFlightReservations);
        result.put("unreplayedDeadLetters", unreplayedDeadLetters);
        result.put("unresolvedRecovery", unresolvedRecovery);
        result.put("admissionLedger", ledger);
        result.put("activeRuntimeMutations", activeRuntimeMutations);
        result.put("activeAdmissionMutations", activeAdmissionMutations);
        result.put("metadataUpdateFences", metadataUpdateFences);
        result.put("redis", redisState);
        result.put("safe", safe);
        return result;
    }

    private Map<String, Object> integritySnapshot() {
        Long tableCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT table_name)
                FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN (
                    'commerce_voucher','commerce_seckill_voucher','shop_summary_projection',
                    'commerce_order','commerce_reservation','commerce_outbox_event',
                    'commerce_inbox_event','commerce_dead_letter','commerce_order_recovery',
                    'commerce_admission_ledger','commerce_id_segment'
                  )
                """, Long.class);
        long negativeStock = count(
                "SELECT COUNT(*) FROM commerce_seckill_voucher WHERE stock < 0"
        );
        long actualTables = tableCount == null ? 0L : tableCount;
        long orderSegment = count("""
                SELECT COUNT(*) FROM commerce_id_segment
                WHERE namespace='order' AND next_value >= 0
                """);
        long voucherSegment = count("""
                SELECT COUNT(*) FROM commerce_id_segment
                WHERE namespace='voucher' AND next_value >= 0
                """);
        return Map.of(
                "requiredTables", REQUIRED_COMMERCE_TABLES,
                "actualTables", actualTables,
                "negativeStockRows", negativeStock,
                "orderIdSegmentSeeded", orderSegment == 1,
                "voucherIdSegmentSeeded", voucherSegment == 1,
                "safe", actualTables == REQUIRED_COMMERCE_TABLES
                        && negativeStock == 0
                        && orderSegment == 1
                        && voucherSegment == 1
        );
    }

    private Map<String, Long> ledgerSnapshot(CommerceRedisKeyspace keyspace) {
        Object[] fingerprint = {
                keyspace.routingGeneration(),
                keyspace.physicalShardCount()
        };
        long accepted = count("""
                SELECT COUNT(*) FROM commerce_admission_ledger
                WHERE routing_generation=? AND physical_shard_count=? AND status='ACCEPTED'
                """, fingerprint);
        long repairRequired = count("""
                SELECT COUNT(*) FROM commerce_admission_ledger
                WHERE routing_generation=? AND physical_shard_count=? AND status='REPAIR_REQUIRED'
                """, fingerprint);
        long activeClaims = count("""
                SELECT COUNT(*) FROM commerce_admission_ledger
                WHERE routing_generation=? AND physical_shard_count=?
                  AND claim_token IS NOT NULL AND claimed_until>=CURRENT_TIMESTAMP(3)
                """, fingerprint);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("accepted", accepted);
        result.put("repairRequired", repairRequired);
        result.put("activeClaims", activeClaims);
        return result;
    }

    private Map<String, Object> redisDrainState(
            CommerceRedisKeyspace keyspace,
            List<Long> voucherIds
    ) {
        long[] inFlightByShard = new long[keyspace.physicalShardCount()];
        for (Long voucherId : voucherIds) {
            Long size = redis.opsForSet().size(keyspace.inflight(voucherId));
            if (size == null) {
                throw new IllegalStateException(
                        "Unable to read Commerce in-flight set for voucher " + voucherId
                );
            }
            inFlightByShard[keyspace.physicalShard(voucherId)] += size;
        }

        long totalPending = 0L;
        long totalLag = 0L;
        long totalInFlight = 0L;
        boolean available = true;
        boolean drained = true;
        List<Map<String, Object>> shards = new ArrayList<>();
        for (int shard = 0; shard < keyspace.streams().size(); shard++) {
            String stream = keyspace.streams().get(shard);
            Map<String, Object> streamState = streamState(stream);
            long pending = number(streamState.get("pending"));
            long lag = number(streamState.get("lag"));
            long inFlight = inFlightByShard[shard];
            boolean shardAvailable = Boolean.TRUE.equals(streamState.get("available"));
            boolean shardDrained = shardAvailable && pending == 0 && lag == 0 && inFlight == 0;
            totalPending += pending;
            totalLag += lag;
            totalInFlight += inFlight;
            available &= shardAvailable;
            drained &= shardDrained;
            Map<String, Object> state = new LinkedHashMap<>(streamState);
            state.put("shard", shard);
            state.put("stream", stream);
            state.put("inFlight", inFlight);
            state.put("drained", shardDrained);
            shards.add(state);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("pending", totalPending);
        result.put("lag", totalLag);
        result.put("inFlight", totalInFlight);
        result.put("drained", available && drained);
        result.put("shards", shards);
        return result;
    }

    private Map<String, Object> streamState(String stream) {
        Boolean exists = redis.hasKey(stream);
        if (!Boolean.TRUE.equals(exists)) {
            return new LinkedHashMap<>(Map.of(
                    "available", true,
                    "exists", false,
                    "pending", 0L,
                    "lag", 0L
            ));
        }
        try {
            Long lengthValue = redis.opsForStream().size(stream);
            long length = lengthValue == null ? -1L : lengthValue;
            if (length < 0) throw new IllegalStateException("Redis Stream length is unavailable");

            StreamInfo.XInfoGroups groups = redis.opsForStream().groups(stream);
            StreamInfo.XInfoGroup group = groups.stream()
                    .filter(candidate -> CommerceOrderService.GROUP.equals(candidate.groupName()))
                    .findFirst()
                    .orElse(null);
            if (group == null) {
                if (length == 0) {
                    return new LinkedHashMap<>(Map.of(
                            "available", true,
                            "exists", true,
                            "length", 0L,
                            "pending", 0L,
                            "lag", 0L,
                            "groupPresent", false
                    ));
                }
                throw new IllegalStateException("Commerce consumer group is missing");
            }
            long pending = group.pendingCount() == null ? 0L : group.pendingCount();
            String delivered = group.lastDeliveredId();
            String generated = redis.opsForStream().info(stream).lastGeneratedId();
            long lag = length == 0 || generated != null && generated.equals(delivered) ? 0L : -1L;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", true);
            result.put("exists", true);
            result.put("length", length);
            result.put("pending", pending);
            result.put("lag", lag);
            result.put("groupPresent", true);
            result.put("lastDeliveredId", Objects.toString(delivered, ""));
            result.put("lastGeneratedId", Objects.toString(generated, ""));
            return result;
        } catch (RuntimeException failure) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", false);
            result.put("exists", true);
            result.put("pending", 0L);
            result.put("lag", -1L);
            result.put("error", failure.getClass().getSimpleName());
            return result;
        }
    }

    private Map<String, Object> targetPreflight(CommerceRedisKeyspace target) {
        List<VoucherProjection> vouchers = voucherProjections();
        Map<Long, Set<String>> expectedBuyers = expectedBuyers();
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> voucherResults = new ArrayList<>();

        for (VoucherProjection voucher : vouchers) {
            String redisStock = redis.opsForValue().get(target.stock(voucher.id()));
            boolean stockMatches = String.valueOf(voucher.stock()).equals(redisStock);
            if (!stockMatches) {
                errors.add("voucher=" + voucher.id() + " target stock mismatch");
            }

            Map<Object, Object> metadata = redis.opsForHash().entries(target.campaignMeta(voucher.id()));
            boolean metadataMatches = String.valueOf(voucher.id()).equals(text(metadata.get("voucherId")))
                    && String.valueOf(voucher.type()).equals(text(metadata.get("type")))
                    && String.valueOf(voucher.status()).equals(text(metadata.get("status")))
                    && String.valueOf(voucher.beginEpochMillis())
                    .equals(text(metadata.get("beginEpochMillis")))
                    && String.valueOf(voucher.endEpochMillis())
                    .equals(text(metadata.get("endEpochMillis")))
                    && voucher.sourceVersion().equals(text(metadata.get("sourceVersion")));
            if (!metadataMatches) {
                errors.add("voucher=" + voucher.id() + " target metadata mismatch");
            }

            Set<String> actualBuyers = redis.opsForSet().members(target.buyers(voucher.id()));
            Set<String> normalizedActual = actualBuyers == null
                    ? Set.of()
                    : new TreeSet<>(actualBuyers);
            Set<String> normalizedExpected = expectedBuyers.getOrDefault(voucher.id(), Set.of());
            boolean buyersMatch = normalizedExpected.equals(normalizedActual);
            if (!buyersMatch) {
                errors.add("voucher=" + voucher.id() + " target buyers mismatch");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("voucherId", voucher.id());
            result.put("shard", target.physicalShard(voucher.id()));
            result.put("stockMatches", stockMatches);
            result.put("metadataMatches", metadataMatches);
            result.put("buyersMatch", buyersMatch);
            result.put("databaseBuyerCount", normalizedExpected.size());
            result.put("redisBuyerCount", normalizedActual.size());
            voucherResults.add(result);
        }

        Map<String, Long> ledger = ledgerSnapshot(target);
        if (ledger.values().stream().anyMatch(value -> value != 0L)) {
            errors.add("target admission ledger is not terminal");
        }
        Map<String, Object> redisState = redisDrainState(
                target,
                vouchers.stream().map(VoucherProjection::id).toList()
        );
        if (!Boolean.TRUE.equals(redisState.get("drained"))) {
            errors.add("target keyspace contains in-flight Stream work");
        }
        long metadataUpdateFences = metadataUpdateFenceCount(
                target,
                vouchers.stream().map(VoucherProjection::id).toList()
        );
        if (metadataUpdateFences != 0L) {
            errors.add("target keyspace contains campaign metadata update fences");
        }
        long activeAdmissionMutations = runtimeLeases.activeAdmissionCount(target);
        if (activeAdmissionMutations != 0L) {
            errors.add("target keyspace contains active admission mutations");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(fingerprint(target));
        result.put("safe", errors.isEmpty());
        result.put("errors", errors);
        result.put("admissionLedger", ledger);
        result.put("vouchers", voucherResults);
        result.put("redis", redisState);
        result.put("metadataUpdateFences", metadataUpdateFences);
        result.put("activeAdmissionMutations", activeAdmissionMutations);
        return result;
    }

    private long metadataUpdateFenceCount(
            CommerceRedisKeyspace keyspace,
            Collection<Long> voucherIds
    ) {
        long fences = 0L;
        for (Long voucherId : voucherIds) {
            if (Boolean.TRUE.equals(redis.hasKey(keyspace.campaignUpdateFence(voucherId)))) {
                fences++;
            }
        }
        return fences;
    }

    private List<VoucherProjection> voucherProjections() {
        return jdbc.query("""
                SELECT s.voucher_id,s.stock,v.type,v.status,s.begin_time,s.end_time,
                       v.version AS voucher_version,s.version AS seckill_version
                FROM commerce_seckill_voucher s
                JOIN commerce_voucher v ON v.id=s.voucher_id
                ORDER BY s.voucher_id
                """, (result, row) -> new VoucherProjection(
                result.getLong("voucher_id"),
                result.getInt("stock"),
                result.getInt("type"),
                result.getInt("status"),
                epochMillis(result.getTimestamp("begin_time")),
                epochMillis(result.getTimestamp("end_time")),
                result.getLong("voucher_version") + ":" + result.getLong("seckill_version")
        ));
    }

    private Map<Long, Set<String>> expectedBuyers() {
        Map<Long, Set<String>> buyers = new LinkedHashMap<>();
        jdbc.query("""
                SELECT voucher_id,user_id FROM (
                  SELECT voucher_id,user_id FROM commerce_order
                  UNION
                  SELECT voucher_id,user_id FROM commerce_reservation WHERE status='RESERVED'
                ) expected_buyers
                ORDER BY voucher_id,user_id
                """, result -> {
            buyers.computeIfAbsent(
                    result.getLong("voucher_id"),
                    ignored -> new TreeSet<>()
            ).add(String.valueOf(result.getLong("user_id")));
        });
        return buyers;
    }

    private List<Long> voucherIds() {
        return jdbc.queryForList(
                "SELECT voucher_id FROM commerce_seckill_voucher ORDER BY voucher_id",
                Long.class
        );
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        if (value == null) throw new IllegalStateException("Commerce drain query returned null");
        return value;
    }

    private CommerceRedisKeyspace targetKeyspace() {
        if (targetGeneration.isBlank()
                || targetPhysicalShardCount < 1
                || targetPhysicalShardCount > 1024) {
            throw new IllegalArgumentException(
                    "Activate requires a valid target routing generation and shard count"
            );
        }
        return new CommerceRedisKeyspace(targetPhysicalShardCount, targetGeneration);
    }

    private CommerceRoutingGuard.RoutingSnapshot requireControl() {
        CommerceRoutingGuard.RoutingSnapshot current = readControl();
        if (current == null) {
            throw new IllegalStateException("Commerce routing control record is missing");
        }
        return current;
    }

    private CommerceRoutingGuard.RoutingSnapshot readControl() {
        return CommerceRoutingGuard.parse(
                redis.opsForValue().get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY)
        );
    }

    private void assertFingerprint(
            CommerceRoutingGuard.RoutingSnapshot snapshot,
            CommerceRedisKeyspace expected
    ) {
        if (snapshot == null || !snapshot.matches(expected)) {
            throw new IllegalStateException(
                    "Commerce routing fingerprint mismatch: expected="
                            + expected.routingGeneration() + "/" + expected.physicalShardCount()
            );
        }
    }

    private String controlValue(CommerceRoutingGuard.RoutingSnapshot snapshot) {
        return new CommerceRedisKeyspace(
                snapshot.physicalShardCount(),
                snapshot.generation()
        ).routingControlValue(snapshot.state().name(), snapshot.revision());
    }

    private String gateValue(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        return state == CommerceRoutingGuard.RoutingState.ACTIVE
                ? keyspace.activeAdmissionGateValue()
                : keyspace.drainingAdmissionGateValue();
    }

    private Map<String, Object> baseResult(String operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("observedAt", Instant.now().toString());
        result.put("source", fingerprint(source));
        return result;
    }

    private Map<String, Object> describe(CommerceRoutingGuard.RoutingSnapshot snapshot) {
        if (snapshot == null) return Map.of("present", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("present", true);
        result.put("state", snapshot.state().name());
        result.put("generation", snapshot.generation());
        result.put("physicalShardCount", snapshot.physicalShardCount());
        result.put("revision", snapshot.revision());
        return result;
    }

    private Map<String, Object> fingerprint(CommerceRedisKeyspace keyspace) {
        return Map.of(
                "generation", keyspace.routingGeneration(),
                "physicalShardCount", keyspace.physicalShardCount()
        );
    }

    private boolean sameFingerprint(
            CommerceRedisKeyspace left,
            CommerceRedisKeyspace right
    ) {
        return left.routingGeneration().equals(right.routingGeneration())
                && left.physicalShardCount() == right.physicalShardCount();
    }

    private long epochMillis(Timestamp value) {
        if (value == null) throw new IllegalStateException("Voucher campaign time is null");
        return value.toInstant().toEpochMilli();
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException("Expected numeric routing evidence");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String revision() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record VoucherProjection(
            long id,
            int stock,
            int type,
            int status,
            long beginEpochMillis,
            long endEpochMillis,
            String sourceVersion
    ) {}

    enum Action {
        STATUS,
        INITIALIZE,
        BEGIN_DRAIN,
        VERIFY_DRAIN,
        ACTIVATE;

        static Action parse(String raw) {
            String normalized = Objects.requireNonNull(raw, "action")
                    .trim()
                    .replace('-', '_')
                    .toUpperCase(Locale.ROOT);
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Unsupported Commerce routing operator action: " + raw,
                        invalid
                );
            }
        }
    }

    static final class RoutingPreflightException extends IllegalStateException {
        private final Map<String, Object> evidence;

        RoutingPreflightException(String message, Map<String, Object> evidence) {
            super(message);
            this.evidence = Map.copyOf(evidence);
        }

        Map<String, Object> evidence() {
            return evidence;
        }
    }
}
