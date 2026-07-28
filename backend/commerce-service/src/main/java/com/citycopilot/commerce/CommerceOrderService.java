package com.citycopilot.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommerceOrderService {
    static final String STOCK_PREFIX = "commerce:seckill:stock:";
    static final String BUYER_PREFIX = "commerce:seckill:buyers:";
    static final String STREAM = "commerce:stream:orders";
    static final String GROUP = "commerce-order-workers";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = script("lua/commerce_seckill.lua");

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
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString()
                : idempotencyKey;
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id,status,version FROM commerce_order WHERE user_id=? AND idempotency_key=? LIMIT 1",
                userId,
                key
        );
        if (!existing.isEmpty()) {
            return orderResult(existing.get(0), "IDEMPOTENT_REPLAY");
        }
        long orderId = orderIds.nextId();
        Long result = redis.execute(
                SECKILL_SCRIPT,
                List.of(STOCK_PREFIX + voucherId, BUYER_PREFIX + voucherId, STREAM),
                String.valueOf(userId),
                String.valueOf(orderId),
                String.valueOf(voucherId),
                key
        );
        if (result == null) {
            throw new CommerceApiException("COMMERCE_REDIS_UNAVAILABLE", "Seckill admission unavailable", 503);
        }
        if (result == 1L) {
            throw new CommerceApiException("COMMERCE_STOCK_EXHAUSTED", "Voucher stock exhausted", 409);
        }
        if (result == 2L) {
            throw new CommerceApiException("COMMERCE_ONE_PER_USER", "User already purchased this voucher", 409);
        }
        return Map.of(
                "orderId", String.valueOf(orderId),
                "state", "processing",
                "code", "COMMERCE_ORDER_ACCEPTED",
                "idempotencyKey", key
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
            process(record);
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        long orderId = number(record, "orderId");
        long userId = number(record, "userId");
        long voucherId = number(record, "voucherId");
        String idempotencyKey = text(record, "idempotencyKey");
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO commerce_reservation(
                            order_id,voucher_id,user_id,status,stream_record_id
                        ) VALUES (?,?,?,'RESERVED',?)
                        """, orderId, voucherId, userId, record.getId().getValue());
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
                event.put("eventId", UUID.randomUUID().toString());
                event.put("eventType", "commerce.order.created");
                event.put("schemaVersion", 1);
                event.put("aggregateType", "commerce_order");
                event.put("aggregateId", String.valueOf(orderId));
                event.put("aggregateVersion", 0);
                event.put("sourceService", "commerce-service");
                event.put("occurredAt", Instant.now().toString());
                event.put("correlationId", String.valueOf(orderId));
                event.put("payload", Map.of(
                        "orderId", orderId,
                        "userId", userId,
                        "voucherId", voucherId,
                        "status", 1,
                        "version", 0
                ));
                jdbc.update("""
                        INSERT INTO commerce_outbox_event(
                            event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                            topic,payload_json,status
                        ) VALUES (?,?,?,?,?,'citycopilot-commerce-events-v1',?,'READY')
                        """,
                        event.get("eventId"), event.get("eventType"), event.get("aggregateType"),
                        event.get("aggregateId"), event.get("aggregateVersion"), json(event)
                );
            });
            redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        } catch (DuplicateKeyException duplicate) {
            Integer identicalOrder = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM commerce_order
                    WHERE id=? AND user_id=? AND voucher_id=? AND idempotency_key=?
                    """, Integer.class, orderId, userId, voucherId, idempotencyKey);
            if (identicalOrder != null && identicalOrder == 1) {
                redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
                return;
            }
            failAndAcknowledge(record, voucherId, userId, duplicate);
        } catch (Exception failure) {
            failAndAcknowledge(record, voucherId, userId, failure);
        }
    }

    private void failAndAcknowledge(
            MapRecord<String, Object, Object> record,
            long voucherId,
            long userId,
            Exception failure
    ) {
        compensate(voucherId, userId);
        jdbc.update("""
                INSERT INTO commerce_dead_letter(
                    source_id,payload_json,error_message
                ) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE error_message=VALUES(error_message), retry_count=retry_count+1
                """,
                record.getId().getValue(),
                json(record.getValue()),
                truncate(failure.getMessage())
        );
        redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
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
            long voucherId = ((Number) before.get("voucher_id")).longValue();
            if ("cancel".equals(action) || "refund".equals(action)) {
                jdbc.update("""
                        UPDATE commerce_seckill_voucher
                        SET stock=stock+1, version=version+1
                        WHERE voucher_id=?
                        """, voucherId);
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
        long voucherId = ((Number) before.get("voucher_id")).longValue();
        if ("cancel".equals(action) || "refund".equals(action)) {
            redis.opsForValue().increment(STOCK_PREFIX + voucherId);
            if ("cancel".equals(action)) {
                redis.opsForSet().remove(BUYER_PREFIX + voucherId, String.valueOf(userId));
            }
        }
        return order(orderId, userId, admin);
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

    private void compensate(long voucherId, long userId) {
        redis.opsForValue().increment(STOCK_PREFIX + voucherId);
        redis.opsForSet().remove(BUYER_PREFIX + voucherId, String.valueOf(userId));
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
}
