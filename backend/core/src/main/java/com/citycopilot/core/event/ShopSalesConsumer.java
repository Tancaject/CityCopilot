package com.citycopilot.core.event;

import com.citycopilot.core.config.KafkaEventProperties;
import com.citycopilot.core.utils.CacheClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 按 Commerce 订单事件维护店铺销量 {@code shop.sold}：已支付、已使用的订单计入，取消、退款不计入。
 *
 * <p>Inbox 登记、订单投影行和销量增减在同一个事务里提交，重复投递只生效一次。投影按订单最后状态计数，
 * 新事件版本高于已存版本才覆盖，因此退款先于支付到达时，迟到的支付会被丢弃，结果仍正确。
 * 没有 shopId 的旧载荷（schemaVersion 1）跳过，由回填脚本覆盖历史订单。</p>
 */
@Component
public class ShopSalesConsumer {
    private final KafkaEventProperties properties;
    private final InboxRepository inboxRepository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<CacheClient> cache;
    private final ObjectProvider<MeterRegistry> metrics;

    public ShopSalesConsumer(KafkaEventProperties properties, InboxRepository inboxRepository, JdbcTemplate jdbc,
                             PlatformTransactionManager transactionManager, ObjectMapper objectMapper,
                             ObjectProvider<CacheClient> cache, ObjectProvider<MeterRegistry> metrics) {
        this.properties = properties;
        this.inboxRepository = inboxRepository;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.metrics = metrics;
    }

    @KafkaListener(
            id = "shop-sales",
            topics = "${citycopilot.kafka.commerce-topic}",
            groupId = "${citycopilot.kafka.shop-sales-consumer-group}",
            concurrency = "2",
            autoStartup = "#{${citycopilot.kafka.enabled:true} and ${citycopilot.kafka.consumer-enabled:true}}"
    )
    void consume(ConsumerRecord<String, byte[]> record) throws Exception {
        byte[] body = record.value();
        JsonNode envelope = objectMapper.readTree(body);
        String eventType = required(envelope, "eventType").asText();
        JsonNode payload = required(envelope, "payload");
        if (!eventType.startsWith("commerce.order.") || !payload.hasNonNull("shopId")) {
            count("skipped");
            return;
        }
        long shopId = payload.get("shopId").asLong();
        String hash = sha256(body);
        String outcome = transactions.execute(status -> {
            InboxOutcome inbox = inboxRepository.process(properties.getShopSalesConsumerGroup(),
                    required(envelope, "eventId").asText(), hash, eventType,
                    required(envelope, "aggregateType").asText(), required(envelope, "aggregateId").asText(),
                    required(envelope, "aggregateVersion").asLong());
            if (inbox != InboxOutcome.PROCESSED) return inbox.name().toLowerCase();
            return apply(required(payload, "orderId").asLong(), shopId, required(payload, "status").asInt(),
                    required(envelope, "aggregateVersion").asLong());
        });
        count(outcome);
        if ("counted".equals(outcome) || "uncounted".equals(outcome)) {
            CacheClient client = cache.getIfAvailable();
            if (client != null) client.invalidateShop(shopId);
        }
    }

    /** @return counted/uncounted 表示销量 +1/-1；unchanged 表示计数不变或事件不比已存状态新 */
    private String apply(long orderId, long shopId, int status, long version) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status,aggregate_version FROM shop_order_sales WHERE order_id=? FOR UPDATE", orderId);
        if (!rows.isEmpty() && version <= ((Number) rows.get(0).get("aggregate_version")).longValue()) {
            return "unchanged";
        }
        boolean before = !rows.isEmpty() && sold(((Number) rows.get(0).get("status")).intValue());
        jdbc.update("""
                INSERT INTO shop_order_sales(order_id,shop_id,status,aggregate_version) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE status=VALUES(status), aggregate_version=VALUES(aggregate_version)
                """, orderId, shopId, status, version);
        int delta = (sold(status) ? 1 : 0) - (before ? 1 : 0);
        if (delta == 0) return "unchanged";
        // sold 是 UNSIGNED：账不平导致减到负数时直接报错，消息重试后进死信并告警，而不是悄悄截断。
        jdbc.update("UPDATE shop SET sold=sold+? WHERE id=?", delta, shopId);
        return delta > 0 ? "counted" : "uncounted";
    }

    /** 已支付(2)、已使用(3)计入销量。 */
    private static boolean sold(int status) {
        return status == 2 || status == 3;
    }

    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Event field is required: " + field);
        }
        return value;
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private void count(String outcome) {
        MeterRegistry registry = metrics.getIfAvailable();
        if (registry != null) {
            Counter.builder("citycopilot.shop_sales.apply").tag("outcome", outcome).register(registry).increment();
        }
    }
}
