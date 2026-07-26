package com.citycopilot.core.event;

import com.citycopilot.contract.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    public void append(DomainEventEnvelope<?> envelope, String topic, String tag) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            jdbcTemplate.update("""
                    INSERT INTO outbox_event(
                        event_id, event_type, schema_version, aggregate_type, aggregate_id,
                        aggregate_version, source_service, trace_id, correlation_id, causation_id,
                        topic, tag, payload_json, payload_hash, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'READY')
                    """,
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.schemaVersion(),
                    envelope.aggregateType(),
                    envelope.aggregateId(),
                    envelope.aggregateVersion(),
                    envelope.sourceService(),
                    envelope.traceId(),
                    envelope.correlationId(),
                    envelope.causationId(),
                    topic,
                    tag,
                    json,
                    sha256(json)
            );
        } catch (Exception error) {
            throw new IllegalStateException("Unable to append outbox event", error);
        }
    }

    @Transactional
    public List<OutboxRecord> claimReady(int limit, Duration lease) {
        long fencingToken = nextFencingToken();
        String claimToken = UUID.randomUUID().toString();
        List<String> candidates = jdbcTemplate.queryForList("""
                SELECT event_id
                FROM outbox_event
                WHERE (
                    status IN ('READY', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP(3)
                ) OR (
                    status = 'CLAIMED' AND claimed_until < CURRENT_TIMESTAMP(3)
                )
                ORDER BY created_at
                LIMIT ?
                """, String.class, Math.max(1, limit));
        Instant claimedUntil = Instant.now().plus(lease);
        for (String eventId : candidates) {
            jdbcTemplate.update("""
                    UPDATE outbox_event
                    SET status='CLAIMED', claim_token=?, fencing_token=?, claimed_until=?,
                        attempts=attempts+1, updated_at=CURRENT_TIMESTAMP(3)
                    WHERE event_id=? AND (
                        (status IN ('READY', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP(3))
                        OR (status='CLAIMED' AND claimed_until < CURRENT_TIMESTAMP(3))
                    )
                    """, claimToken, fencingToken, Timestamp.from(claimedUntil), eventId);
        }
        return jdbcTemplate.query("""
                SELECT event_id, event_type, topic, tag, payload_json, payload_hash,
                       claim_token, fencing_token, attempts
                FROM outbox_event
                WHERE claim_token=? AND fencing_token=? AND status='CLAIMED'
                ORDER BY created_at
                """,
                (rs, row) -> new OutboxRecord(
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("topic"),
                        rs.getString("tag"),
                        rs.getString("payload_json"),
                        rs.getString("payload_hash"),
                        rs.getString("claim_token"),
                        rs.getLong("fencing_token"),
                        rs.getInt("attempts")
                ),
                claimToken,
                fencingToken
        );
    }

    public boolean markPublished(OutboxRecord record) {
        return jdbcTemplate.update("""
                UPDATE outbox_event
                SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP(3), claimed_until=NULL,
                    last_error=NULL, updated_at=CURRENT_TIMESTAMP(3)
                WHERE event_id=? AND status='CLAIMED' AND claim_token=? AND fencing_token=?
                """, record.eventId(), record.claimToken(), record.fencingToken()) == 1;
    }

    public boolean markFailed(OutboxRecord record, String error, Duration backoff, int maxAttempts) {
        String target = record.attempts() >= maxAttempts ? "DEAD" : "READY";
        return jdbcTemplate.update("""
                UPDATE outbox_event
                SET status=?, next_attempt_at=?, claimed_until=NULL, last_error=?,
                    updated_at=CURRENT_TIMESTAMP(3)
                WHERE event_id=? AND status='CLAIMED' AND claim_token=? AND fencing_token=?
                """,
                target,
                Timestamp.from(Instant.now().plus(backoff)),
                truncate(error, 1024),
                record.eventId(),
                record.claimToken(),
                record.fencingToken()
        ) == 1;
    }

    public long readyCount() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status IN ('READY','FAILED','CLAIMED')",
                Long.class
        );
        return value == null ? 0 : value;
    }

    public long deadCount() {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status='DEAD'",
                Long.class
        );
        return value == null ? 0 : value;
    }

    public double oldestReadyAgeSeconds() {
        Double value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(3)) / 1000000.0, 0)
                FROM outbox_event
                WHERE status IN ('READY','FAILED','CLAIMED')
                """, Double.class);
        return value == null ? 0 : value;
    }

    @Transactional
    protected long nextFencingToken() {
        jdbcTemplate.update("""
                INSERT INTO outbox_fence(fence_name, current_token)
                VALUES ('commerce-outbox', LAST_INSERT_ID(1))
                ON DUPLICATE KEY UPDATE current_token=LAST_INSERT_ID(current_token+1)
                """);
        Long token = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return token == null ? 0 : token;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
