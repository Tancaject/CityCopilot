package com.citycopilot.commerce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommerceOperationsService {
    private final JdbcTemplate jdbc;
    private final CommerceOrderService orders;
    private final ObjectMapper objectMapper;

    public CommerceOperationsService(
            JdbcTemplate jdbc,
            CommerceOrderService orders,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> summary(Instant fromInclusive, Instant toExclusive) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "A valid summary time range is required",
                    400
            );
        }
        return jdbc.queryForObject("""
                SELECT
                    o.orders,
                    o.today_orders,
                    o.unpaid_orders,
                    o.cancelled_orders,
                    v.vouchers,
                    v.seckill_vouchers,
                    v.active_vouchers
                FROM (
                    SELECT
                        COUNT(*) AS orders,
                        COUNT(CASE WHEN create_time >= ? AND create_time < ? THEN 1 END) AS today_orders,
                        COUNT(CASE WHEN status = 1 THEN 1 END) AS unpaid_orders,
                        COUNT(CASE WHEN status = 4 THEN 1 END) AS cancelled_orders
                    FROM commerce_order
                ) o
                CROSS JOIN (
                    SELECT
                        COUNT(*) AS vouchers,
                        COUNT(CASE WHEN type = 1 THEN 1 END) AS seckill_vouchers,
                        COUNT(CASE WHEN status = 1 THEN 1 END) AS active_vouchers
                    FROM commerce_voucher
                ) v
                """,
                (rs, rowNum) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("orders", rs.getLong("orders"));
                    result.put("todayOrders", rs.getLong("today_orders"));
                    result.put("unpaidOrders", rs.getLong("unpaid_orders"));
                    result.put("cancelledOrders", rs.getLong("cancelled_orders"));
                    result.put("vouchers", rs.getLong("vouchers"));
                    result.put("seckillVouchers", rs.getLong("seckill_vouchers"));
                    result.put("activeVouchers", rs.getLong("active_vouchers"));
                    return Map.copyOf(result);
                },
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive)
        );
    }

    public List<Map<String, Object>> deadLetters(Integer limit) {
        int bounded = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        return jdbc.queryForList("""
                SELECT dead.source_id,dead.error_message,dead.retry_count,
                       dead.created_at,dead.replayed_at,
                       recovery.phase AS recovery_phase
                FROM commerce_dead_letter dead
                LEFT JOIN commerce_order_recovery recovery
                  ON recovery.source_id=dead.source_id
                ORDER BY dead.created_at DESC
                LIMIT ?
                """, bounded).stream().map(this::normalizeDeadLetter).toList();
    }

    @Transactional
    public Map<String, Object> replayDeadLetter(String sourceId, String actor) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "sourceId is required",
                    400
            );
        }
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT dead.source_id,dead.payload_json,dead.replayed_at,
                           recovery.phase AS recovery_phase
                    FROM commerce_dead_letter dead
                    LEFT JOIN commerce_order_recovery recovery
                      ON recovery.source_id=dead.source_id
                    WHERE dead.source_id=?
                    """, sourceId);
        } catch (EmptyResultDataAccessException missing) {
            throw new CommerceApiException(
                    "COMMERCE_DEAD_LETTER_NOT_FOUND",
                    "Dead letter not found",
                    404
            );
        }
        if (row.get("replayed_at") != null) {
            return Map.of("status", "already_replayed", "deadLetterRecordId", sourceId);
        }
        if ("REPAIR_REQUIRED".equals(String.valueOf(row.get("recovery_phase")))) {
            throw new CommerceApiException(
                    "COMMERCE_INVENTORY_REPAIR_REQUIRED",
                    "Repair and verify this voucher inventory before replaying the dead letter",
                    409
            );
        }
        Map<String, Object> payload = payload(row.get("payload_json"));
        long orderId = positiveLong(payload.get("orderId"), "orderId");
        long voucherId = positiveLong(payload.get("voucherId"), "voucherId");
        long userId = positiveLong(payload.get("userId"), "userId");
        String idempotencyKey = payload.get("idempotencyKey") == null
                ? "dead-letter-order:" + orderId
                : String.valueOf(payload.get("idempotencyKey"));
        Map<String, Object> accepted = orders.replayAdmission(
                orderId,
                voucherId,
                userId,
                idempotencyKey
        );
        int marked = jdbc.update("""
                UPDATE commerce_dead_letter
                SET replayed_at=CURRENT_TIMESTAMP(3)
                WHERE source_id=? AND replayed_at IS NULL
                """, sourceId);
        if (marked != 1) {
            return Map.of("status", "already_replayed", "deadLetterRecordId", sourceId);
        }
        appendReplayAudit(sourceId, orderId, voucherId, userId, actor);
        Map<String, Object> result = new LinkedHashMap<>(accepted);
        result.put("status", "replayed");
        result.put("deadLetterRecordId", sourceId);
        return Map.copyOf(result);
    }

    private Map<String, Object> payload(Object raw) {
        try {
            Map<String, Object> value = objectMapper.readValue(
                    String.valueOf(raw),
                    new TypeReference<LinkedHashMap<String, Object>>() {}
            );
            if (value == null) {
                throw new IllegalArgumentException("payload is null");
            }
            return value;
        } catch (Exception error) {
            throw new CommerceApiException(
                    "COMMERCE_DEAD_LETTER_INVALID",
                    "Dead-letter payload cannot be replayed",
                    409
            );
        }
    }

    private long positiveLong(Object value, String name) {
        try {
            long result = value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(value));
            if (result <= 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (RuntimeException error) {
            throw new CommerceApiException(
                    "COMMERCE_DEAD_LETTER_INVALID",
                    "Dead-letter " + name + " is invalid",
                    409
            );
        }
    }

    private Map<String, Object> normalizeDeadLetter(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", row.get("source_id"));
        result.put("error", row.get("error_message"));
        result.put("retryCount", row.get("retry_count"));
        result.put("createdAt", row.get("created_at"));
        result.put("replayedAt", row.get("replayed_at"));
        result.put("recoveryPhase", row.get("recovery_phase"));
        return result;
    }

    private void appendReplayAudit(
            String sourceId,
            long orderId,
            long voucherId,
            long userId,
            String actor
    ) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "commerce.dead-letter.replayed");
        event.put("schemaVersion", 1);
        event.put("aggregateType", "commerce_dead_letter");
        event.put("aggregateId", sourceId);
        event.put("aggregateVersion", 1);
        event.put("sourceService", "commerce-service");
        event.put("occurredAt", Instant.now().toString());
        event.put("correlationId", String.valueOf(orderId));
        event.put("payload", Map.of(
                "sourceId", sourceId,
                "orderId", orderId,
                "voucherId", voucherId,
                "userId", userId,
                "actor", actor == null || actor.isBlank() ? "unknown" : actor
        ));
        try {
            jdbc.update("""
                    INSERT INTO commerce_outbox_event(
                        event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                        topic,payload_json,status
                    ) VALUES (?,?,?,?,?,'citycopilot-ops-events-v1',?,'READY')
                    """,
                    eventId,
                    event.get("eventType"),
                    event.get("aggregateType"),
                    event.get("aggregateId"),
                    event.get("aggregateVersion"),
                    objectMapper.writeValueAsString(event)
            );
        } catch (Exception error) {
            throw new IllegalStateException("Unable to persist dead-letter replay audit", error);
        }
    }
}
