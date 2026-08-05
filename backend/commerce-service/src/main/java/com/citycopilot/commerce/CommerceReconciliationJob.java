package com.citycopilot.commerce;

import com.citycopilot.contract.event.StockDiscrepancyDetectedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "citycopilot.commerce",
        name = "reconciliation-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CommerceReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(CommerceReconciliationJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final CommerceConsistencyService consistency;
    private final CommerceRedisCampaignMetadata campaignMetadata;
    private final ObjectMapper objectMapper;

    public CommerceReconciliationJob(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            CommerceConsistencyService consistency,
            CommerceRedisCampaignMetadata campaignMetadata,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.consistency = consistency;
        this.campaignMetadata = campaignMetadata;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${citycopilot.commerce.reconciliation-ms:5000}")
    void reconcile() {
        for (Long voucherId : jdbc.queryForList(
                "SELECT voucher_id FROM commerce_seckill_voucher",
                Long.class
        )) {
            try {
                reconcileVoucher(voucherId);
            } catch (RuntimeException failure) {
                log.error(
                        "Commerce reconciliation failed for voucher {}; continuing with remaining vouchers",
                        voucherId,
                        failure
                );
            }
        }
    }

    private void reconcileVoucher(long voucherId) {
        // This role is deployed as a singleton. Re-publishing from the
        // database repairs metadata removed by a rolled-back admin update
        // or by a failed afterCommit Redis refresh. Any refresh failure
        // deletes the key and therefore keeps admission fail-closed.
        campaignMetadata.refreshAfterCommit(voucherId);
        Map<String, Object> snapshot = consistency.snapshotForReconciliation(voucherId);
        String streakKey = "commerce:metrics:seckill:discrepancy-streak:" + voucherId;
        if (Boolean.TRUE.equals(snapshot.get("consistent"))) {
            redis.delete(streakKey);
            return;
        }
        if (Boolean.TRUE.equals(snapshot.get("reconciliationFenceRequired"))) {
            // Stop new admissions on the first quiescent stock/buyer
            // mismatch. Incident de-duplication remains streak-based, but
            // safety no longer waits for alert publication.
            consistency.ensureReconciliationFence(voucherId);
        }
        Long streak = redis.opsForValue().increment(streakKey);
        redis.expire(streakKey, Duration.ofHours(1));
        if (streak == null || streak < 2) return;
        String incidentKey = "seckill-stock:" + voucherId;
        Boolean claimed = redis.opsForValue().setIfAbsent(
                "commerce:event:stock-discrepancy:cooldown:" + voucherId,
                incidentKey,
                Duration.ofMinutes(5)
        );
        if (Boolean.TRUE.equals(claimed)) {
            appendEvent(voucherId, snapshot, incidentKey);
        }
    }

    private void appendEvent(long voucherId, Map<String, Object> snapshot, String incidentKey) {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("voucherId", voucherId);
        payload.put("databaseStock", snapshot.get("databaseStock"));
        payload.put("redisStock", snapshot.get("redisStock"));
        payload.put("activeReservations", snapshot.get("consumingReservations"));
        payload.put("incidentKey", incidentKey);
        payload.put("detectedAt", now.toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", StockDiscrepancyDetectedEvent.EVENT_TYPE);
        envelope.put("schemaVersion", StockDiscrepancyDetectedEvent.SCHEMA_VERSION);
        envelope.put("aggregateType", "commerce_seckill_voucher");
        envelope.put("aggregateId", String.valueOf(voucherId));
        envelope.put("aggregateVersion", System.currentTimeMillis());
        envelope.put("sourceService", "commerce-service");
        envelope.put("occurredAt", now.toString());
        envelope.put("correlationId", incidentKey);
        envelope.put("payload", payload);
        try {
            jdbc.update("""
                    INSERT INTO commerce_outbox_event(
                        event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                        topic,payload_json,status
                    ) VALUES (?,?,?,?,?,'citycopilot-ops-events-v1',?,'READY')
                    """,
                    eventId,
                    envelope.get("eventType"),
                    envelope.get("aggregateType"),
                    envelope.get("aggregateId"),
                    envelope.get("aggregateVersion"),
                    objectMapper.writeValueAsString(envelope)
            );
        } catch (Exception error) {
            throw new IllegalStateException("Unable to append commerce discrepancy event", error);
        }
    }
}
