package com.citycopilot.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class CommerceConsistencyService {
    private static final Logger log = LoggerFactory.getLogger(CommerceConsistencyService.class);
    private static final DefaultRedisScript<Long> REPAIR_SCRIPT =
            script("lua/commerce_repair_inventory.lua");
    private static final DefaultRedisScript<Long> RELEASE_FENCE_SCRIPT =
            script("lua/commerce_release_fence.lua");
    static final String RECONCILIATION_FENCE_TOKEN = "commerce-reconciliation:v1";
    private static final String REPAIR_FENCE_TOKEN_PREFIX = "commerce-repair:v1:";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CommerceRedisKeyspace keyspace;
    private final CommerceRedisReplicationExecutor replicatedRedis;
    private final CommerceRuntimeMutationLeases runtimeLeases;
    private final CommerceRoutingGuard routing;

    @Autowired
    public CommerceConsistencyService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            CommerceRedisKeyspace keyspace,
            CommerceRedisReplicationExecutor replicatedRedis,
            CommerceRuntimeMutationLeases runtimeLeases,
            CommerceRoutingGuard routing
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyspace = keyspace;
        this.replicatedRedis = replicatedRedis;
        this.runtimeLeases = runtimeLeases;
        this.routing = routing;
    }

    CommerceConsistencyService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(jdbc, redis, objectMapper, new CommerceRedisKeyspace(1, "0"), null, null, null);
    }

    public Map<String, Object> snapshot(long voucherId) {
        return capture(voucherId, true).evidence();
    }

    Map<String, Object> snapshotForReconciliation(long voucherId) {
        return capture(voucherId, false).evidence();
    }

    private ConsistencyCapture capture(long voucherId, boolean includeGlobalDiagnostics) {
        Integer databaseStock = jdbc.queryForObject(
                "SELECT stock FROM commerce_seckill_voucher WHERE voucher_id=?",
                Integer.class,
                voucherId
        );
        String raw = redis.opsForValue().get(keyspace.stock(voucherId));
        Integer redisStock = parseStock(raw);
        List<Long> databaseBuyerIds = jdbc.queryForList("""
                SELECT user_id FROM commerce_order WHERE voucher_id=?
                UNION
                SELECT user_id FROM commerce_reservation
                WHERE voucher_id=? AND status='RESERVED'
                ORDER BY user_id
                """, Long.class, voucherId, voucherId);
        List<String> databaseBuyers = sortedStrings(databaseBuyerIds);
        Set<String> rawRedisBuyers = redis.opsForSet().members(
                keyspace.buyers(voucherId)
        );
        boolean redisBuyerSetAvailable = rawRedisBuyers != null;
        List<String> redisBuyers = sortedStrings(
                rawRedisBuyers == null ? List.of() : rawRedisBuyers
        );
        List<String> missingRedisBuyers = difference(databaseBuyers, redisBuyers);
        List<String> unexpectedRedisBuyers = difference(redisBuyers, databaseBuyers);
        Long voucherInFlight = redis.opsForSet().size(
                keyspace.inflight(voucherId)
        );
        List<Map<String, Object>> unresolved = jdbc.queryForList("""
                SELECT r.order_id,r.status,
                       CASE WHEN o.id IS NULL THEN 0 ELSE 1 END order_exists
                FROM commerce_reservation r
                LEFT JOIN commerce_order o ON o.id=r.order_id
                WHERE r.voucher_id=? AND r.status IN ('RESERVED','RELEASE_REQUIRED','ORDER_CREATED')
                ORDER BY r.order_id
                """, voucherId);
        List<Map<String, Object>> recoveryRows = jdbc.queryForList("""
                SELECT source_id,source_type,order_id,user_id,phase,compensation_delta
                FROM commerce_order_recovery
                WHERE voucher_id=?
                  AND phase IN (
                    'RECEIVED','COMPENSATION_REQUIRED','RESTORE_PENDING','REPAIR_REQUIRED'
                  )
                ORDER BY source_id
                """, voucherId);
        List<Map<String, Object>> repairRequiredRecovery = recoveryRows.stream()
                .filter(row -> "REPAIR_REQUIRED".equals(String.valueOf(row.get("phase"))))
                .toList();
        List<Map<String, Object>> activeRecovery = recoveryRows.stream()
                .filter(row -> !"REPAIR_REQUIRED".equals(String.valueOf(row.get("phase"))))
                .toList();
        int repairRequiredCompensationDelta = repairRequiredRecovery.stream()
                .map(row -> row.get("compensation_delta"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToInt(Number::intValue)
                .sum();
        int consuming = 0;
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> row : unresolved) {
            String status = String.valueOf(row.get("status"));
            boolean orderExists = ((Number) row.get("order_exists")).intValue() == 1;
            if ("ORDER_CREATED".equals(status) && orderExists) continue;
            if ("RESERVED".equals(status) && !orderExists) {
                consuming++;
                continue;
            }
            conflicts.add(row);
        }
        int expected = databaseStock - consuming;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("voucherId", voucherId);
        evidence.put("databaseStock", databaseStock);
        evidence.put("redisStock", redisStock);
        evidence.put("expectedRedisStock", expected);
        evidence.put("databaseBuyerCount", databaseBuyers.size());
        evidence.put("redisBuyerCount", redisBuyerSetAvailable ? redisBuyers.size() : null);
        evidence.put("databaseBuyerHash", hash(databaseBuyers));
        evidence.put("redisBuyerHash", redisBuyerSetAvailable ? hash(redisBuyers) : null);
        evidence.put("missingRedisBuyers", missingRedisBuyers);
        evidence.put("unexpectedRedisBuyers", unexpectedRedisBuyers);
        evidence.put("redisBuyerSetAvailable", redisBuyerSetAvailable);
        evidence.put("voucherInFlight", voucherInFlight);
        evidence.put("consumingReservations", consuming);
        evidence.put("reservationRowsInspected", unresolved.size());
        evidence.put("conflicts", conflicts);
        evidence.put("repairRequiredRecoveryCount", repairRequiredRecovery.size());
        evidence.put("repairRequiredCompensationDelta", repairRequiredCompensationDelta);
        evidence.put("repairRequiredRecoveryRows", repairRequiredRecovery);
        evidence.put("activeRecoveryCount", activeRecovery.size());
        evidence.put("activeRecoveryRows", activeRecovery);
        boolean stockBuyerConsistent = redisStock != null
                && redisStock == expected
                && redisBuyerSetAvailable
                && missingRedisBuyers.isEmpty()
                && unexpectedRedisBuyers.isEmpty();
        boolean inventoryConsistent = stockBuyerConsistent
                && voucherInFlight != null
                && voucherInFlight == 0L
                && conflicts.isEmpty();
        evidence.put("stockBuyerConsistent", stockBuyerConsistent);
        evidence.put(
                "reconciliationFenceRequired",
                !stockBuyerConsistent && voucherInFlight != null && voucherInFlight == 0L
        );
        evidence.put("inventoryConsistent", inventoryConsistent);
        evidence.put(
                "consistent",
                inventoryConsistent && recoveryRows.isEmpty()
        );
        evidence.put("ownerService", "commerce-service");
        String evidenceHash = hash(evidence);

        if (includeGlobalDiagnostics) {
            // Shared-stream state is useful operational context, but it is not
            // voucher evidence and therefore must not influence consistency or CAS repair.
            evidence.put("globalStreamHealth", globalStreamHealth());
        }
        evidence.put("collectedAt", Instant.now().toString());
        evidence.put("evidenceHash", evidenceHash);
        return new ConsistencyCapture(evidence, List.copyOf(databaseBuyerIds));
    }

    public Map<String, Object> repair(long voucherId, String evidenceHash) {
        if (runtimeLeases == null) return repairWithLease(voucherId, evidenceHash, null);
        try (CommerceRuntimeMutationLeases.MutationLease lease = runtimeLeases.acquireMutation()) {
            routing.requireRuntimeMutationActive();
            Map<String, Object> result = repairWithLease(voucherId, evidenceHash, lease);
            lease.renewAndAssert();
            return result;
        }
    }

    void ensureReconciliationFence(long voucherId) {
        String fenceKey = keyspace.fence(voucherId);
        if (replicatedRedis == null) {
            redis.opsForValue().setIfAbsent(fenceKey, RECONCILIATION_FENCE_TOKEN);
            return;
        }
        replicatedRedis.setIfAbsent(
                "reconciliation_inventory_fence",
                fenceKey,
                RECONCILIATION_FENCE_TOKEN
        );
    }

    private Map<String, Object> repairWithLease(
            long voucherId,
            String evidenceHash,
            CommerceRuntimeMutationLeases.MutationLease runtimeLease
    ) {
        RepairFence repairFence = acquireRepairFence(voucherId, evidenceHash);
        boolean repairCompleted = false;
        try {
            ConsistencyCapture capture = capture(voucherId, false);
            Map<String, Object> before = capture.evidence();
            requireEvidenceHash(before, evidenceHash);
            if (before.get("voucherInFlight") == null) {
                throw new CommerceApiException(
                        "COMMERCE_INFLIGHT_LEDGER_UNAVAILABLE",
                        "Voucher in-flight ledger is unavailable",
                        503
                );
            }
            if (((Number) before.get("voucherInFlight")).longValue() != 0L) {
                throw new CommerceApiException(
                        "COMMERCE_VOUCHER_IN_FLIGHT",
                        "Voucher still has admitted orders in flight",
                        409
                );
            }
            if (!((List<?>) before.get("conflicts")).isEmpty()) {
                throw new CommerceApiException(
                        "COMMERCE_RESERVATION_CONFLICT",
                        "Reservation conflict blocks repair",
                        409
                );
            }
            if (!((List<?>) before.get("activeRecoveryRows")).isEmpty()) {
                throw new CommerceApiException(
                        "COMMERCE_RECOVERY_IN_PROGRESS",
                        "Voucher recovery must reach a repairable terminal phase first",
                        409
                );
            }
            int target = ((Number) before.get("expectedRedisStock")).intValue();
            List<Long> buyers = capture.databaseBuyerIds();
            List<String> arguments = new ArrayList<>();
            arguments.add(repairFence.token());
            arguments.add(String.valueOf(target));
            buyers.forEach(userId -> arguments.add(String.valueOf(userId)));
            renewAndAssert(runtimeLease);
            Long repaired = redis.execute(
                    REPAIR_SCRIPT,
                    List.of(
                            repairFence.key(),
                            keyspace.stock(voucherId),
                            keyspace.buyers(voucherId)
                    ),
                    arguments.toArray()
            );
            if (repaired == null || repaired < 0) {
                throw new CommerceApiException(
                        "COMMERCE_STALE_EVIDENCE",
                        "Inventory repair fence ownership changed before publication",
                        409
                );
            }
            Map<String, Object> repairedInventory = capture(voucherId, false).evidence();
            if (!Boolean.TRUE.equals(repairedInventory.get("inventoryConsistent"))) {
                throw new CommerceApiException(
                        "COMMERCE_REPAIR_VERIFICATION_FAILED",
                        "Inventory repair could not be verified",
                        409
                );
            }
            renewAndAssert(runtimeLease);
            int restoredRecoveryRows = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase='RESTORED', compensation_delta=0,
                        finalized_at=COALESCE(finalized_at,CURRENT_TIMESTAMP(3))
                    WHERE voucher_id=? AND phase='REPAIR_REQUIRED'
                    """, voucherId);
            Map<String, Object> after = capture(voucherId, false).evidence();
            if (!Boolean.TRUE.equals(after.get("consistent"))) {
                throw new CommerceApiException(
                        "COMMERCE_REPAIR_VERIFICATION_FAILED",
                        "Inventory repair could not be verified",
                        409
                );
            }
            renewAndAssert(runtimeLease);
            releaseVerifiedRepairFence(repairFence);
            repairCompleted = true;
            return Map.of(
                    "voucherId", voucherId,
                    "targetRedisStock", target,
                    "rebuiltBuyerCount", buyers.size(),
                    "restoredRecoveryRows", restoredRecoveryRows,
                    "beforeEvidenceHash", evidenceHash,
                    "afterEvidenceHash", after.get("evidenceHash"),
                    "verified", true
            );
        } finally {
            if (!repairCompleted) {
                retainAutomaticFence(repairFence);
            }
        }
    }

    private RepairFence acquireRepairFence(long voucherId, String evidenceHash) {
        String fenceKey = keyspace.fence(voucherId);
        String repairToken = REPAIR_FENCE_TOKEN_PREFIX + UUID.randomUUID();
        if (replicatedRedis == null) {
            Boolean acquired = redis.opsForValue().setIfAbsent(fenceKey, repairToken);
            if (Boolean.TRUE.equals(acquired)) {
                return new RepairFence(fenceKey, repairToken);
            }
        } else {
            CommerceRedisReplicationExecutor.ReplicatedMutation acquired;
            try {
                acquired = replicatedRedis.setIfAbsent(
                        "consistency_repair_fence",
                        fenceKey,
                        repairToken
                );
            } catch (RuntimeException failure) {
                retainAutomaticFence(new RepairFence(fenceKey, repairToken));
                throw failure;
            }
            if (acquired != CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT) {
                return new RepairFence(fenceKey, repairToken);
            }
        }

        String currentOwner = redis.opsForValue().get(fenceKey);
        if (!RECONCILIATION_FENCE_TOKEN.equals(currentOwner)) {
            throw inventoryMaintenance();
        }

        // The automatic fence already blocks admissions, so this first hash
        // check cannot race a new 202. A second check after the ownership CAS
        // protects against worker/recovery progress during the hand-off.
        requireEvidenceHash(capture(voucherId, false).evidence(), evidenceHash);
        if (replicatedRedis == null) {
            throw inventoryMaintenance();
        }
        CommerceRedisReplicationExecutor.ReplicatedMutation takeover;
        try {
            takeover = replicatedRedis.compareAndSet(
                    "consistency_repair_takeover",
                    fenceKey,
                    RECONCILIATION_FENCE_TOKEN,
                    repairToken
            );
        } catch (RuntimeException failure) {
            retainAutomaticFence(new RepairFence(fenceKey, repairToken));
            throw failure;
        }
        if (takeover == CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT) {
            throw inventoryMaintenance();
        }
        return new RepairFence(fenceKey, repairToken);
    }

    private void releaseVerifiedRepairFence(RepairFence repairFence) {
        if (replicatedRedis == null) {
            Long released = redis.execute(
                    RELEASE_FENCE_SCRIPT,
                    List.of(repairFence.key()),
                    repairFence.token()
            );
            if (released == null || released == 0L) {
                throw inventoryMaintenance();
            }
            return;
        }
        CommerceRedisReplicationExecutor.ReplicatedMutation released =
                replicatedRedis.deleteIfEquals(
                        "consistency_repair_release",
                        repairFence.key(),
                        repairFence.token()
                );
        if (released == CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT) {
            throw inventoryMaintenance();
        }
    }

    private void retainAutomaticFence(RepairFence repairFence) {
        if (replicatedRedis == null) return;
        try {
            CommerceRedisReplicationExecutor.ReplicatedMutation restored =
                    replicatedRedis.compareAndSet(
                            "consistency_repair_restore_reconciliation_fence",
                            repairFence.key(),
                            repairFence.token(),
                            RECONCILIATION_FENCE_TOKEN
                    );
            if (restored == CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT
                    && redis.opsForValue().get(repairFence.key()) == null) {
                replicatedRedis.setIfAbsent(
                        "consistency_repair_restore_missing_fence",
                        repairFence.key(),
                        RECONCILIATION_FENCE_TOKEN
                );
            }
        } catch (RuntimeException restoreFailure) {
            // A retained repair token is itself fail-closed. Log loudly because
            // a missing key plus a failed restore needs operator intervention.
            log.error(
                    "Unable to restore automatic Commerce inventory fence for {}",
                    repairFence.key(),
                    restoreFailure
            );
        }
    }

    private void requireEvidenceHash(Map<String, Object> evidence, String expectedHash) {
        if (!String.valueOf(evidence.get("evidenceHash")).equals(expectedHash)) {
            throw new CommerceApiException(
                    "COMMERCE_STALE_EVIDENCE",
                    "Consistency evidence changed",
                    409
            );
        }
    }

    private CommerceApiException inventoryMaintenance() {
        return new CommerceApiException(
                "COMMERCE_INVENTORY_MAINTENANCE",
                "Voucher inventory is already under maintenance",
                409
        );
    }

    private void renewAndAssert(CommerceRuntimeMutationLeases.MutationLease runtimeLease) {
        if (runtimeLease != null) runtimeLease.renewAndAssert();
    }

    public Map<String, Object> verify(long voucherId) {
        Map<String, Object> snapshot = snapshot(voucherId);
        return Map.of(
                "voucherId", voucherId,
                "verified", snapshot.get("consistent"),
                "evidenceHash", snapshot.get("evidenceHash"),
                "snapshot", snapshot
        );
    }

    private Integer parseStock(String raw) {
        if (raw == null) return null;
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public Map<String, Object> globalStreamHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("scope", "global");
        health.put("streams", keyspace.streams());
        health.put("consumerGroup", CommerceOrderService.GROUP);
        List<Map<String, Object>> shardHealth = new ArrayList<>();
        long totalPending = 0L;
        boolean available = true;
        boolean drained = true;
        for (String streamKey : keyspace.streams()) {
            Map<String, Object> shard = new LinkedHashMap<>();
            shard.put("stream", streamKey);
            try {
                StreamInfo.XInfoGroups groups = redis.opsForStream().groups(streamKey);
                StreamInfo.XInfoGroup group = groups.stream()
                        .filter(candidate -> CommerceOrderService.GROUP.equals(candidate.groupName()))
                        .findFirst()
                        .orElseThrow();
                StreamInfo.XInfoStream stream = redis.opsForStream().info(streamKey);
                long pending = group.pendingCount() == null ? 0L : group.pendingCount();
                String delivered = group.lastDeliveredId();
                String generated = stream.lastGeneratedId();
                boolean shardDrained = pending == 0
                        && generated != null
                        && generated.equals(delivered);
                totalPending += pending;
                drained &= shardDrained;
                shard.put("available", true);
                shard.put("pending", pending);
                shard.put("drained", shardDrained);
                shard.put("lastDeliveredId", delivered);
                shard.put("lastGeneratedId", generated);
            } catch (RuntimeException unavailableShard) {
                available = false;
                drained = false;
                shard.put("available", false);
                shard.put("error", "stream diagnostics unavailable");
            }
            shardHealth.add(shard);
        }
        health.put("available", available);
        health.put("pending", totalPending);
        health.put("drained", drained);
        health.put("shards", shardHealth);
        return health;
    }

    private List<String> sortedStrings(Collection<?> values) {
        TreeSet<String> sorted = new TreeSet<>();
        values.stream()
                .filter(value -> value != null)
                .map(String::valueOf)
                .forEach(sorted::add);
        return List.copyOf(sorted);
    }

    private List<String> difference(List<String> left, List<String> right) {
        Set<String> difference = new LinkedHashSet<>(left);
        difference.removeAll(new LinkedHashSet<>(right));
        return List.copyOf(difference);
    }

    private String hash(Object evidence) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(evidence).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash commerce consistency evidence", error);
        }
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private record ConsistencyCapture(
            Map<String, Object> evidence,
            List<Long> databaseBuyerIds
    ) {}

    private record RepairFence(
            String key,
            String token
    ) {}
}
