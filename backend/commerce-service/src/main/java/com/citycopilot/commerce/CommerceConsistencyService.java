package com.citycopilot.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
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
    private static final DefaultRedisScript<Long> REPAIR_SCRIPT =
            script("lua/commerce_repair_inventory.lua");
    private static final DefaultRedisScript<Long> RELEASE_FENCE_SCRIPT =
            script("lua/commerce_release_fence.lua");
    private static final Duration FENCE_TTL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CommerceConsistencyService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
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
        String raw = redis.opsForValue().get(CommerceOrderService.STOCK_PREFIX + voucherId);
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
                CommerceOrderService.BUYER_PREFIX + voucherId
        );
        boolean redisBuyerSetAvailable = rawRedisBuyers != null;
        List<String> redisBuyers = sortedStrings(
                rawRedisBuyers == null ? List.of() : rawRedisBuyers
        );
        List<String> missingRedisBuyers = difference(databaseBuyers, redisBuyers);
        List<String> unexpectedRedisBuyers = difference(redisBuyers, databaseBuyers);
        Long voucherInFlight = redis.opsForSet().size(
                CommerceOrderService.INFLIGHT_PREFIX + voucherId
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
        boolean inventoryConsistent = redisStock != null
                && redisStock == expected
                && redisBuyerSetAvailable
                && missingRedisBuyers.isEmpty()
                && unexpectedRedisBuyers.isEmpty()
                && voucherInFlight != null
                && voucherInFlight == 0L
                && conflicts.isEmpty();
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
        String fenceKey = CommerceOrderService.FENCE_PREFIX + voucherId;
        String fenceToken = UUID.randomUUID().toString();
        Boolean fenced = redis.opsForValue().setIfAbsent(fenceKey, fenceToken, FENCE_TTL);
        if (!Boolean.TRUE.equals(fenced)) {
            throw new CommerceApiException(
                    "COMMERCE_INVENTORY_MAINTENANCE",
                    "Voucher inventory is already under maintenance",
                    409
            );
        }
        try {
            ConsistencyCapture capture = capture(voucherId, false);
            Map<String, Object> before = capture.evidence();
            if (!String.valueOf(before.get("evidenceHash")).equals(evidenceHash)) {
                throw new CommerceApiException("COMMERCE_STALE_EVIDENCE", "Consistency evidence changed", 409);
            }
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
            arguments.add(fenceToken);
            arguments.add(String.valueOf(target));
            buyers.forEach(userId -> arguments.add(String.valueOf(userId)));
            Long repaired = redis.execute(
                    REPAIR_SCRIPT,
                    List.of(
                            fenceKey,
                            CommerceOrderService.STOCK_PREFIX + voucherId,
                            CommerceOrderService.BUYER_PREFIX + voucherId
                    ),
                    arguments.toArray()
            );
            if (repaired == null || repaired < 0) {
                throw new CommerceApiException(
                        "COMMERCE_STALE_EVIDENCE",
                        "Inventory repair fence expired before publication",
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
            int restoredRecoveryRows = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase='RESTORED', compensation_delta=0,
                        finalized_at=COALESCE(finalized_at,CURRENT_TIMESTAMP(3))
                    WHERE voucher_id=? AND phase='REPAIR_REQUIRED'
                    """, voucherId);
            Map<String, Object> after = capture(voucherId, false).evidence();
            return Map.of(
                    "voucherId", voucherId,
                    "targetRedisStock", target,
                    "rebuiltBuyerCount", buyers.size(),
                    "restoredRecoveryRows", restoredRecoveryRows,
                    "beforeEvidenceHash", evidenceHash,
                    "afterEvidenceHash", after.get("evidenceHash"),
                    "verified", after.get("consistent")
            );
        } finally {
            try {
                redis.execute(
                        RELEASE_FENCE_SCRIPT,
                        List.of(fenceKey),
                        fenceToken
                );
            } catch (Exception ignored) {
                // The fence has a bounded TTL; retain the primary repair result.
            }
        }
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
        health.put("stream", CommerceOrderService.STREAM);
        health.put("consumerGroup", CommerceOrderService.GROUP);
        try {
            StreamInfo.XInfoGroups groups = redis.opsForStream().groups(CommerceOrderService.STREAM);
            StreamInfo.XInfoGroup group = groups.stream()
                    .filter(candidate -> CommerceOrderService.GROUP.equals(candidate.groupName()))
                    .findFirst()
                    .orElseThrow();
            StreamInfo.XInfoStream stream = redis.opsForStream().info(CommerceOrderService.STREAM);
            long pending = group.pendingCount() == null ? 0L : group.pendingCount();
            String delivered = group.lastDeliveredId();
            String generated = stream.lastGeneratedId();
            health.put("available", true);
            health.put("pending", pending);
            health.put("drained", pending == 0 && generated != null && generated.equals(delivered));
            health.put("lastDeliveredId", delivered);
            health.put("lastGeneratedId", generated);
        } catch (RuntimeException unavailable) {
            health.put("available", false);
            health.put("error", "stream diagnostics unavailable");
        }
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
}
