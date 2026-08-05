package com.citycopilot.commerce;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "citycopilot.commerce",
        name = "outbox-relay-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CommerceOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(CommerceOutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String endpoints;
    private final boolean sslEnabled;
    private final String accessKey;
    private final String secretKey;
    private final MeterRegistry metrics;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private Producer producer;

    public CommerceOutboxRelay(
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            @Value("${citycopilot.commerce.rocketmq-enabled:true}") boolean enabled,
            @Value("${citycopilot.commerce.rocketmq-endpoints:127.0.0.1:18081}") String endpoints,
            @Value("${citycopilot.commerce.rocketmq-ssl-enabled:false}") boolean sslEnabled,
            @Value("${citycopilot.commerce.rocketmq-access-key:}") String accessKey,
            @Value("${citycopilot.commerce.rocketmq-secret-key:}") String secretKey
    ) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.enabled = enabled;
        this.endpoints = endpoints;
        this.sslEnabled = sslEnabled;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        Gauge.builder("citycopilot.commerce.outbox.unpublished", jdbc, ignored -> unpublished())
                .register(metrics);
        Gauge.builder("citycopilot.commerce.outbox.oldest_ready_age_seconds", jdbc, ignored -> oldestReadyAgeSeconds())
                .register(metrics);
    }

    @PostConstruct
    void start() throws Exception {
        if (!enabled) return;
        producer = provider.newProducerBuilder()
                .setClientConfiguration(clientConfiguration(endpoints, sslEnabled, accessKey, secretKey))
                .setTopics("citycopilot-commerce-events-v1", "citycopilot-ops-events-v1")
                .setMaxAttempts(3)
                .build();
    }

    static ClientConfiguration clientConfiguration(
            String endpoints,
            boolean sslEnabled,
            String configuredAccessKey,
            String configuredSecretKey
    ) {
        ClientConfigurationBuilder builder = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(sslEnabled);
        String accessKey = configuredAccessKey == null ? "" : configuredAccessKey.trim();
        String secretKey = configuredSecretKey == null ? "" : configuredSecretKey.trim();
        if (!accessKey.isEmpty() || !secretKey.isEmpty()) {
            if (accessKey.isEmpty() || secretKey.isEmpty()) {
                throw new IllegalStateException("RocketMQ access key and secret key must be configured together");
            }
            builder.setCredentialProvider(new StaticSessionCredentialsProvider(accessKey, secretKey));
        }
        return builder.build();
    }

    @Scheduled(fixedDelayString = "${citycopilot.commerce.outbox-relay-ms:1000}")
    void relay() {
        if (producer == null) return;
        for (String eventId : relayCandidates()) {
            try {
                publish(eventId);
            } catch (RuntimeException failure) {
                log.warn("Unable to relay Commerce outbox event {}", eventId, failure);
            }
        }
    }

    List<String> relayCandidates() {
        return jdbc.queryForList("""
                SELECT event_id FROM commerce_outbox_event
                 WHERE (status IN ('READY','FAILED','DEAD')
                       AND next_attempt_at <= CURRENT_TIMESTAMP(3))
                   OR (status='CLAIMED'
                       AND claimed_until < CURRENT_TIMESTAMP(3))
                ORDER BY created_at
                LIMIT 50
                """, String.class);
    }

    void publish(String eventId) {
        OutboxClaim claim = claim(eventId);
        if (claim == null) return;
        Timer.Sample sample = Timer.start(metrics);
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT topic,event_type,payload_json FROM commerce_outbox_event
                    WHERE event_id=? AND claim_token=? AND fencing_token=? AND status='CLAIMED'
                    """, eventId, claim.token(), claim.fencingToken());
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
            if (markPublished(claim)) {
                count("published");
            } else {
                count("lost_claim");
                log.warn("Commerce outbox ownership changed after publishing {}", eventId);
            }
        } catch (Exception error) {
            if (markFailed(claim, error)) {
                count("failed");
            } else {
                count("lost_claim");
                log.warn("Commerce outbox ownership changed while handling failure for {}", eventId);
            }
        } finally {
            sample.stop(Timer.builder("citycopilot.commerce.rocketmq.publish.duration")
                    .register(metrics));
        }
    }

    OutboxClaim claim(String eventId) {
        String claim = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE commerce_outbox_event
                SET status='CLAIMED', claim_token=?, fencing_token=COALESCE(fencing_token,0)+1,
                    claimed_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),
                    attempts=attempts+1
                WHERE event_id=?
                   AND ((status IN ('READY','FAILED','DEAD')
                        AND next_attempt_at <= CURRENT_TIMESTAMP(3))
                       OR (status='CLAIMED'
                           AND claimed_until < CURRENT_TIMESTAMP(3)))
                """, claim, eventId);
        if (claimed != 1) return null;
        Long fencingToken = jdbc.queryForObject("""
                SELECT fencing_token FROM commerce_outbox_event
                WHERE event_id=? AND claim_token=? AND status='CLAIMED'
                """, Long.class, eventId, claim);
        if (fencingToken == null) {
            throw new IllegalStateException("Claimed Commerce outbox event has no fencing token");
        }
        return new OutboxClaim(eventId, claim, fencingToken);
    }

    boolean markPublished(OutboxClaim claim) {
        return jdbc.update("""
                UPDATE commerce_outbox_event
                SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP(3),
                    claim_token=NULL, claimed_until=NULL, last_error=NULL
                WHERE event_id=? AND claim_token=? AND fencing_token=? AND status='CLAIMED'
                """, claim.eventId(), claim.token(), claim.fencingToken()) == 1;
    }

    boolean markFailed(OutboxClaim claim, Exception error) {
        return jdbc.update("""
                UPDATE commerce_outbox_event
                SET status='FAILED',
                    next_attempt_at=TIMESTAMPADD(
                        SECOND,
                        LEAST(900, CAST(5 * POW(2, LEAST(GREATEST(attempts - 1, 0), 8)) AS UNSIGNED)),
                        CURRENT_TIMESTAMP(3)
                    ),
                    claim_token=NULL, claimed_until=NULL, last_error=?
                WHERE event_id=? AND claim_token=? AND fencing_token=? AND status='CLAIMED'
                """, truncate(error.getMessage()), claim.eventId(),
                claim.token(), claim.fencingToken()) == 1;
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
            "SELECT MIN(created_at) FROM commerce_outbox_event WHERE status IN ('READY','FAILED','CLAIMED','DEAD')",
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

    record OutboxClaim(String eventId, String token, long fencingToken) {
    }
}
