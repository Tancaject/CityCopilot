package com.citycopilot.commerce;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CommerceOutboxRelay {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String endpoints;
    private final MeterRegistry metrics;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private Producer producer;

    public CommerceOutboxRelay(
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            @Value("${citycopilot.commerce.rocketmq-enabled:true}") boolean enabled,
            @Value("${citycopilot.commerce.rocketmq-endpoints:127.0.0.1:18081}") String endpoints
    ) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.enabled = enabled;
        this.endpoints = endpoints;
        Gauge.builder("citycopilot.commerce.outbox.unpublished", jdbc, ignored -> unpublished())
                .register(metrics);
        Gauge.builder("citycopilot.commerce.outbox.oldest_ready_age_seconds", jdbc, ignored -> oldestReadyAgeSeconds())
                .register(metrics);
    }

    @PostConstruct
    void start() throws Exception {
        if (!enabled) return;
        producer = provider.newProducerBuilder()
                .setClientConfiguration(ClientConfiguration.newBuilder().setEndpoints(endpoints).build())
                .setTopics("citycopilot-commerce-events-v1", "citycopilot-ops-events-v1")
                .setMaxAttempts(3)
                .build();
    }

    @Scheduled(fixedDelayString = "${citycopilot.commerce.outbox-relay-ms:1000}")
    void relay() {
        if (producer == null) return;
        List<String> eventIds = jdbc.queryForList("""
                SELECT event_id FROM commerce_outbox_event
                WHERE status IN ('READY','FAILED')
                  AND next_attempt_at <= CURRENT_TIMESTAMP(3)
                ORDER BY created_at
                LIMIT 50
                """, String.class);
        for (String eventId : eventIds) {
            publish(eventId);
        }
    }

    private void publish(String eventId) {
        String claim = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE commerce_outbox_event
                SET status='CLAIMED', claim_token=?, fencing_token=COALESCE(fencing_token,0)+1,
                    claimed_until=?, attempts=attempts+1
                WHERE event_id=? AND status IN ('READY','FAILED')
                """, claim, Timestamp.from(Instant.now().plusSeconds(30)), eventId);
        if (claimed != 1) return;
        Timer.Sample sample = Timer.start(metrics);
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT topic,event_type,payload_json FROM commerce_outbox_event
                    WHERE event_id=? AND claim_token=?
                    """, eventId, claim);
            String payload = String.valueOf(row.get("payload_json"));
            Message message = provider.newMessageBuilder()
                    .setTopic(String.valueOf(row.get("topic")))
                    .setTag(String.valueOf(row.get("event_type")).contains("discrepancy")
                            ? "StockDiscrepancyDetected"
                            : "CommerceOrderEvent")
                    .setKeys(eventId)
                    .addProperty("eventType", String.valueOf(row.get("event_type")))
                    .addProperty("payloadHash", sha256(payload))
                    .setBody(payload.getBytes(StandardCharsets.UTF_8))
                    .build();
            producer.send(message);
            jdbc.update("""
                    UPDATE commerce_outbox_event
                    SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP(3),
                        claim_token=NULL, claimed_until=NULL, last_error=NULL
                    WHERE event_id=? AND claim_token=? AND status='CLAIMED'
                    """, eventId, claim);
            count("published");
        } catch (Exception error) {
            jdbc.update("""
                    UPDATE commerce_outbox_event
                    SET status=CASE WHEN attempts>=10 THEN 'DEAD' ELSE 'FAILED' END,
                        next_attempt_at=CURRENT_TIMESTAMP(3)+INTERVAL 5 SECOND,
                        claim_token=NULL, claimed_until=NULL, last_error=?
                    WHERE event_id=? AND claim_token=?
                    """, truncate(error.getMessage()), eventId, claim);
            count("failed");
        } finally {
            sample.stop(Timer.builder("citycopilot.commerce.rocketmq.publish.duration")
                    .register(metrics));
        }
    }

    private long unpublished() {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox_event WHERE status <> 'PUBLISHED'",
                Long.class
        );
        return value == null ? 0 : value;
    }

    private double oldestReadyAgeSeconds() {
        Timestamp oldest = jdbc.queryForObject(
                "SELECT MIN(created_at) FROM commerce_outbox_event WHERE status IN ('READY','FAILED','CLAIMED')",
                Timestamp.class
        );
        return oldest == null ? 0 : Math.max(0, (System.currentTimeMillis() - oldest.getTime()) / 1000.0);
    }

    private void count(String outcome) {
        io.micrometer.core.instrument.Counter.builder("citycopilot.commerce.outbox.publish")
                .tag("outcome", outcome)
                .register(metrics)
                .increment();
    }

    private String sha256(String payload) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String truncate(String value) {
        String safe = value == null ? "unknown_error" : value;
        return safe.substring(0, Math.min(1024, safe.length()));
    }

    @PreDestroy
    void close() throws Exception {
        if (producer != null) producer.close();
    }
}
