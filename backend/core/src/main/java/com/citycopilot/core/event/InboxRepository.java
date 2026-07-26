package com.citycopilot.core.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class InboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public InboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public InboxOutcome process(
            String consumerGroup,
            String eventId,
            String payloadHash,
            String eventType,
            String aggregateType,
            String aggregateId,
            long aggregateVersion
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO processed_event(
                    consumer_group, event_id, payload_hash, event_type, aggregate_id,
                    aggregate_version, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED')
                """,
                consumerGroup,
                eventId,
                payloadHash,
                eventType,
                aggregateId,
                aggregateVersion
        );
        if (inserted == 0) {
            List<ExistingEvent> existing = jdbcTemplate.query("""
                    SELECT payload_hash, status
                    FROM processed_event
                    WHERE consumer_group=? AND event_id=?
                    """,
                    (rs, row) -> new ExistingEvent(
                            rs.getString("payload_hash"),
                            rs.getString("status")
                    ),
                    consumerGroup,
                    eventId
            );
            if (!existing.isEmpty() && existing.get(0).payloadHash().equals(payloadHash)) {
                return InboxOutcome.DUPLICATE;
            }
            jdbcTemplate.update("""
                    UPDATE processed_event
                    SET status='QUARANTINED',
                        quarantine_reason='same eventId received with a different payload hash',
                        processed_at=CURRENT_TIMESTAMP(3)
                    WHERE consumer_group=? AND event_id=?
                    """, consumerGroup, eventId);
            return InboxOutcome.QUARANTINED;
        }

        Long currentVersion = jdbcTemplate.queryForObject("""
                SELECT MAX(current_version)
                FROM event_projection_version
                WHERE consumer_group=? AND aggregate_type=? AND aggregate_id=?
                """, Long.class, consumerGroup, aggregateType, aggregateId);
        InboxOutcome outcome = currentVersion != null && aggregateVersion < currentVersion
                ? InboxOutcome.STALE
                : InboxOutcome.PROCESSED;
        if (outcome == InboxOutcome.PROCESSED) {
            jdbcTemplate.update("""
                    INSERT INTO event_projection_version(
                        consumer_group, aggregate_type, aggregate_id, current_version
                    ) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        current_version=GREATEST(current_version, VALUES(current_version))
                    """, consumerGroup, aggregateType, aggregateId, aggregateVersion);
        }
        jdbcTemplate.update("""
                UPDATE processed_event
                SET status=?, processed_at=CURRENT_TIMESTAMP(3)
                WHERE consumer_group=? AND event_id=?
                """, outcome.name(), consumerGroup, eventId);
        return outcome;
    }

    private record ExistingEvent(String payloadHash, String status) {
    }
}
