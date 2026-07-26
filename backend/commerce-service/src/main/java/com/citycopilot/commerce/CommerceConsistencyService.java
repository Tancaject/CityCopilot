package com.citycopilot.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CommerceConsistencyService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CommerceConsistencyService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> snapshot(long voucherId) {
        Integer databaseStock = jdbc.queryForObject(
                "SELECT stock FROM commerce_seckill_voucher WHERE voucher_id=?",
                Integer.class,
                voucherId
        );
        String raw = redis.opsForValue().get(CommerceOrderService.STOCK_PREFIX + voucherId);
        Integer redisStock = parseStock(raw);
        List<Map<String, Object>> unresolved = jdbc.queryForList("""
                SELECT r.order_id,r.status,
                       CASE WHEN o.id IS NULL THEN 0 ELSE 1 END order_exists
                FROM commerce_reservation r
                LEFT JOIN commerce_order o ON o.id=r.order_id
                WHERE r.voucher_id=? AND r.status IN ('RESERVED','RELEASE_REQUIRED','ORDER_CREATED')
                """, voucherId);
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
        evidence.put("consumingReservations", consuming);
        evidence.put("reservationRowsInspected", unresolved.size());
        evidence.put("conflicts", conflicts);
        evidence.put("consistent", redisStock != null && redisStock == expected && conflicts.isEmpty());
        evidence.put("ownerService", "commerce-service");
        evidence.put("collectedAt", Instant.now().toString());
        evidence.put("evidenceHash", hash(evidence));
        return evidence;
    }

    public Map<String, Object> repair(long voucherId, String evidenceHash) {
        Map<String, Object> before = snapshot(voucherId);
        if (!String.valueOf(before.get("evidenceHash")).equals(evidenceHash)) {
            throw new CommerceApiException("COMMERCE_STALE_EVIDENCE", "Consistency evidence changed", 409);
        }
        if (!((List<?>) before.get("conflicts")).isEmpty()) {
            throw new CommerceApiException("COMMERCE_RESERVATION_CONFLICT", "Reservation conflict blocks repair", 409);
        }
        int target = ((Number) before.get("expectedRedisStock")).intValue();
        redis.opsForValue().set(CommerceOrderService.STOCK_PREFIX + voucherId, String.valueOf(target));
        Map<String, Object> after = snapshot(voucherId);
        return Map.of(
                "voucherId", voucherId,
                "targetRedisStock", target,
                "beforeEvidenceHash", evidenceHash,
                "afterEvidenceHash", after.get("evidenceHash"),
                "verified", after.get("consistent")
        );
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

    private String hash(Map<String, Object> evidence) {
        try {
            Map<String, Object> stable = new LinkedHashMap<>(evidence);
            stable.remove("collectedAt");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(stable).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash commerce consistency evidence", error);
        }
    }
}
