package com.citycopilot.core.service;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.commerce.CommercePortClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.citycopilot.core.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class SeckillConsistencyService {
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;
    private final CommercePortClient commercePortClient;
    @Value("${citycopilot.commerce.enabled:false}")
    private boolean commerceEnabled;

    public SeckillConsistencyService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            OperationLogService operationLogService,
            ObjectMapper objectMapper,
            CommercePortClient commercePortClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.operationLogService = operationLogService;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.commercePortClient = commercePortClient;
    }

    @AgentTool(
            name = "get_seckill_consistency_snapshot",
            description = "Collect bounded MySQL, Redis, Reservation, order, and Outbox evidence for one voucher",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill-consistency",
            rateLimitPerMinute = 30
    )
    public Map<String, Object> snapshot(Long voucherId) {
        requireVoucherId(voucherId);
        if (commerceEnabled) {
            return commercePortClient.consistency(voucherId);
        }
        Integer databaseStock = jdbcTemplate.queryForObject(
                "SELECT stock FROM seckill_coupon WHERE voucher_id=?",
                Integer.class,
                voucherId
        );
        String redisRaw = redisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
        Integer redisStock = parseStock(redisRaw);
        List<ReservationRow> reservations = jdbcTemplate.query("""
                SELECT r.order_id, r.status, r.version,
                       CASE WHEN o.id IS NULL THEN 0 ELSE 1 END AS order_exists
                FROM seckill_reservation r
                LEFT JOIN coupon_order o ON o.id=r.order_id
                WHERE r.voucher_id=?
                  AND r.status IN ('RESERVED','RELEASE_REQUIRED','ORDER_CREATED')
                ORDER BY r.order_id
                LIMIT 1000
                """,
                (rs, row) -> new ReservationRow(
                        rs.getLong("order_id"),
                        rs.getString("status"),
                        rs.getInt("version"),
                        rs.getBoolean("order_exists")
                ),
                voucherId
        );

        int consumingReservations = 0;
        int createdReservations = 0;
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (ReservationRow reservation : reservations) {
            Object hotStatusValue = redisTemplate.opsForHash()
                    .get(SECKILL_RESERVATION_KEY + reservation.orderId(), "status");
            String hotStatus = hotStatusValue == null ? null : String.valueOf(hotStatusValue);
            if (reservation.orderExists()) {
                createdReservations++;
                if (!SeckillReservationService.ORDER_CREATED.equals(reservation.status())
                        || (hotStatus != null && !SeckillReservationService.ORDER_CREATED.equals(hotStatus))) {
                    conflicts.add(conflict(reservation, hotStatus, "order_exists_but_reservation_not_confirmed"));
                }
                continue;
            }
            if (SeckillReservationService.ORDER_CREATED.equals(reservation.status())) {
                conflicts.add(conflict(reservation, hotStatus, "reservation_confirmed_but_order_missing"));
                continue;
            }
            if (hotStatus == null) {
                conflicts.add(conflict(reservation, null, "redis_hot_reservation_missing"));
                continue;
            }
            if (SeckillReservationService.RELEASED.equals(hotStatus)) {
                if (!SeckillReservationService.RELEASE_REQUIRED.equals(reservation.status())) {
                    conflicts.add(conflict(reservation, hotStatus, "redis_released_from_unexpected_db_state"));
                }
                continue;
            }
            if (SeckillReservationService.RESERVED.equals(hotStatus)) {
                consumingReservations++;
                continue;
            }
            conflicts.add(conflict(reservation, hotStatus, "unrecognized_reservation_state_pair"));
        }
        int expectedRedisStock = databaseStock == null ? 0 : databaseStock - consumingReservations;
        Long outboxUnpublished = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_event
                WHERE aggregate_type='coupon_order'
                  AND status <> 'PUBLISHED'
                  AND aggregate_id IN (
                      SELECT CAST(id AS CHAR) FROM coupon_order WHERE voucher_id=?
                  )
                """, Long.class, voucherId);
        Long outboxDead = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_event
                WHERE aggregate_type='coupon_order'
                  AND status='DEAD'
                  AND aggregate_id IN (
                      SELECT CAST(id AS CHAR) FROM coupon_order WHERE voucher_id=?
                  )
                """, Long.class, voucherId);
        boolean consistent = databaseStock != null
                && redisStock != null
                && conflicts.isEmpty()
                && redisStock == expectedRedisStock;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("voucherId", voucherId);
        evidence.put("databaseStock", databaseStock);
        evidence.put("redisStock", redisStock);
        evidence.put("expectedRedisStock", expectedRedisStock);
        evidence.put("consumingReservations", consumingReservations);
        evidence.put("createdReservations", createdReservations);
        evidence.put("reservationRowsInspected", reservations.size());
        evidence.put("outboxUnpublished", outboxUnpublished == null ? 0 : outboxUnpublished);
        evidence.put("outboxDead", outboxDead == null ? 0 : outboxDead);
        evidence.put("conflicts", conflicts);
        evidence.put("consistent", consistent);
        evidence.put("collectedAt", Instant.now().toString());
        evidence.put("evidenceHash", evidenceHash(evidence));
        return Map.copyOf(evidence);
    }

    @AgentTool(
            name = "preview_seckill_stock_repair",
            description = "Preview a bounded Redis stock repair from fresh consistency evidence without writing",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill-consistency",
            rateLimitPerMinute = 20
    )
    public Map<String, Object> previewRepair(Long voucherId) {
        Map<String, Object> snapshot = snapshot(voucherId);
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("voucherId", voucherId);
        preview.put("currentRedisStock", snapshot.get("redisStock"));
        preview.put("targetRedisStock", snapshot.get("expectedRedisStock"));
        preview.put("consistent", snapshot.get("consistent"));
        preview.put("blocked", !((List<?>) snapshot.get("conflicts")).isEmpty());
        preview.put("evidenceHash", snapshot.get("evidenceHash"));
        preview.put("evidenceExpiresAt", Instant.now().plusSeconds(120).toString());
        return preview;
    }

    @AgentTool(
            name = "repair_seckill_stock",
            description = "Repair one Redis stock key only when fresh evidence hash still matches and no Reservation conflict exists",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS, PermissionCodes.ORDER_WRITE},
            audience = ToolAudience.OPS,
            sideEffect = ToolSideEffect.IDEMPOTENT_WRITE,
            risk = ToolRiskLevel.HIGH,
            approval = ToolApprovalPolicy.OPS_APPROVAL,
            scope = "ops:seckill-consistency",
            rateLimitPerMinute = 10
    )
    public Map<String, Object> repairStock(Long voucherId, String expectedEvidenceHash) {
        if (commerceEnabled) {
            return commercePortClient.repairConsistency(voucherId, expectedEvidenceHash);
        }
        Map<String, Object> before = snapshot(voucherId);
        if (expectedEvidenceHash == null || !expectedEvidenceHash.equals(before.get("evidenceHash"))) {
            throw new IllegalStateException("STALE_EVIDENCE: consistency evidence changed before repair");
        }
        if (!((List<?>) before.get("conflicts")).isEmpty()) {
            throw new IllegalStateException("RESERVATION_CONFLICT: automatic stock repair is blocked");
        }
        int target = ((Number) before.get("expectedRedisStock")).intValue();
        redisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, String.valueOf(target));
        Map<String, Object> after = snapshot(voucherId);
        operationLogService.record(
                "seckill",
                "stock_repair",
                "seckill_voucher",
                voucherId,
                String.valueOf(voucherId),
                before,
                after,
                Boolean.TRUE.equals(after.get("consistent")),
                Boolean.TRUE.equals(after.get("consistent")) ? null : "postcondition_failed"
        );
        return Map.of(
                "voucherId", voucherId,
                "targetRedisStock", target,
                "beforeEvidenceHash", before.get("evidenceHash"),
                "afterEvidenceHash", after.get("evidenceHash"),
                "verified", after.get("consistent")
        );
    }

    @AgentTool(
            name = "verify_seckill_consistency",
            description = "Independently recollect consistency evidence and evaluate the repair postcondition",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill-consistency",
            rateLimitPerMinute = 30
    )
    public Map<String, Object> verify(Long voucherId) {
        if (commerceEnabled) {
            return commercePortClient.verifyConsistency(voucherId);
        }
        Map<String, Object> snapshot = snapshot(voucherId);
        return Map.of(
                "voucherId", voucherId,
                "verified", snapshot.get("consistent"),
                "evidenceHash", snapshot.get("evidenceHash"),
                "snapshot", snapshot
        );
    }

    private Map<String, Object> conflict(ReservationRow row, String hotStatus, String reason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("orderId", row.orderId());
        value.put("databaseStatus", row.status());
        value.put("redisStatus", hotStatus == null ? "<missing>" : hotStatus);
        value.put("reason", reason);
        return Map.copyOf(value);
    }

    private Integer parseStock(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private String evidenceHash(Map<String, Object> evidence) {
        try {
            Map<String, Object> stable = new LinkedHashMap<>(evidence);
            stable.remove("collectedAt");
            byte[] json = objectMapper.writeValueAsBytes(stable);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json)
            );
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash consistency evidence", error);
        }
    }

    private void requireVoucherId(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            throw new IllegalArgumentException("voucherId must be positive");
        }
    }

    private record ReservationRow(long orderId, String status, int version, boolean orderExists) {
    }
}
