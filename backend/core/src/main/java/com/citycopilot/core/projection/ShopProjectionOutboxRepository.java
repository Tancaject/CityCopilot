package com.citycopilot.core.projection;

import com.citycopilot.core.entity.Shop;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ShopProjectionOutboxRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ShopProjectionOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Advances the source version and appends ShopChanged atomically with the
     * caller's shop transaction.
     */
    public long append(Shop before, Shop after) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ShopChanged outbox append requires an active transaction");
        }
        if (after == null || after.getId() == null) {
            throw new IllegalArgumentException("after shop and id are required");
        }
        // Rebuild cutover briefly upgrades this shared row lock to an
        // exclusive lock. Because this method runs in the business
        // transaction, a closed watermark contains every committed shop
        // change before the cutover and none from after it.
        jdbc.queryForObject("""
                SELECT lease_name FROM shop_projection_lease
                WHERE lease_name=?
                FOR SHARE
                """, String.class, ShopProjectionLeaseService.CUTOVER_BARRIER);
        int updated = jdbc.update("""
                UPDATE shop
                SET projection_version=projection_version+1
                WHERE id=?
                """, after.getId());
        if (updated != 1) {
            throw new IllegalStateException("Unable to advance shop projection version: " + after.getId());
        }
        Long version = jdbc.queryForObject(
                "SELECT projection_version FROM shop WHERE id=?",
                Long.class,
                after.getId()
        );
        if (version == null || version <= 0L) {
            throw new IllegalStateException("Invalid shop projection version: " + after.getId());
        }
        after.setProjectionVersion(version);
        String eventId = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO shop_projection_outbox(
                    event_id, shop_id, aggregate_version, event_type, payload_json, status
                ) VALUES (?, ?, ?, 'ShopChanged', ?, 'READY')
                """, eventId, after.getId(), version, payload(before, after, version));
        if (inserted != 1) {
            throw new IllegalStateException("Unable to append ShopChanged outbox event: " + after.getId());
        }
        return version;
    }

    public List<ClaimedEvent> claimReady(int requestedLimit, String owner, long claimTtlMicros) {
        int limit = Math.max(1, Math.min(500, requestedLimit));
        List<Long> candidates = jdbc.queryForList("""
                SELECT id FROM shop_projection_outbox
                WHERE ((status IN ('READY','FAILED','DEAD') AND next_attempt_at<=CURRENT_TIMESTAMP(3))
                    OR (status='CLAIMED' AND claimed_until<CURRENT_TIMESTAMP(3)))
                ORDER BY id
                LIMIT ?
                """, Long.class, limit);
        List<ClaimedEvent> claimedEvents = new ArrayList<>(candidates.size());
        for (Long id : candidates) {
            String claimToken = UUID.randomUUID().toString();
            int claimed = jdbc.update("""
                    UPDATE shop_projection_outbox
                    SET status='CLAIMED', claim_token=?, claim_owner=?,
                        claimed_until=TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(3)),
                        attempts=attempts+1
                    WHERE id=?
                      AND ((status IN ('READY','FAILED','DEAD') AND next_attempt_at<=CURRENT_TIMESTAMP(3))
                        OR (status='CLAIMED' AND claimed_until<CURRENT_TIMESTAMP(3)))
                    """, claimToken, owner, Math.max(1L, claimTtlMicros), id);
            if (claimed != 1) {
                continue;
            }
            List<ClaimedEvent> rows = jdbc.query("""
                    SELECT id,event_id,shop_id,aggregate_version,attempts,claim_token
                    FROM shop_projection_outbox
                    WHERE id=? AND claim_token=? AND status='CLAIMED'
                    """, (rs, rowNum) -> new ClaimedEvent(
                    rs.getLong("id"),
                    rs.getString("event_id"),
                    rs.getLong("shop_id"),
                    rs.getLong("aggregate_version"),
                    rs.getInt("attempts"),
                    rs.getString("claim_token")
            ), id, claimToken);
            if (rows.size() == 1) {
                claimedEvents.add(rows.get(0));
            }
        }
        return claimedEvents;
    }

    public void complete(ClaimedEvent event) {
        int updated = jdbc.update("""
                UPDATE shop_projection_outbox
                SET status='PROCESSED', processed_at=CURRENT_TIMESTAMP(3),
                    claim_token=NULL, claim_owner=NULL, claimed_until=NULL, last_error=NULL
                WHERE id=? AND claim_token=? AND status='CLAIMED'
                """, event.id(), event.claimToken());
        requireOwnedClaim(updated, event, "complete");
    }

    public void fail(ClaimedEvent event, Throwable failure, Duration maxRetryDelay) {
        long delaySeconds = retryDelaySeconds(event.attempts(), maxRetryDelay);
        int updated = jdbc.update("""
                UPDATE shop_projection_outbox
                SET status='FAILED',
                    next_attempt_at=TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)),
                    claim_token=NULL, claim_owner=NULL, claimed_until=NULL, last_error=?
                WHERE id=? AND claim_token=? AND status='CLAIMED'
                """, delaySeconds, truncate(failure), event.id(), event.claimToken());
        requireOwnedClaim(updated, event, "fail");
    }

    public long highWatermark() {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM shop_projection_outbox", Long.class);
        return value == null ? 0L : value;
    }

    public List<ProjectionChange> changesAfter(
            long exclusiveId,
            long inclusiveId,
            int requestedLimit
    ) {
        if (inclusiveId <= exclusiveId) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(500, requestedLimit));
        return jdbc.query("""
                SELECT id,shop_id,aggregate_version
                FROM shop_projection_outbox
                WHERE id>? AND id<=?
                ORDER BY id
                LIMIT ?
                """, (rs, rowNum) -> new ProjectionChange(
                rs.getLong("id"),
                rs.getLong("shop_id"),
                rs.getLong("aggregate_version")
        ), exclusiveId, inclusiveId, limit);
    }

    private String payload(Shop before, Shop after, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "ShopChanged");
        payload.put("shopId", after.getId());
        payload.put("aggregateVersion", version);
        payload.put("previousTypeId", before == null ? null : before.getTypeId());
        payload.put("typeId", after.getTypeId());
        payload.put("geoStatus", after.getGeoStatus());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize ShopChanged", error);
        }
    }

    private String truncate(Throwable failure) {
        String value = failure == null || failure.getMessage() == null
                ? "unknown projection failure"
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return value.substring(0, Math.min(1024, value.length()));
    }

    private long retryDelaySeconds(int attempts, Duration maxRetryDelay) {
        long maximumSeconds = Math.max(1L, maxRetryDelay == null ? 900L : maxRetryDelay.toSeconds());
        int exponent = Math.min(30, Math.max(0, attempts - 1));
        return Math.min(maximumSeconds, 1L << exponent);
    }

    private void requireOwnedClaim(int updated, ClaimedEvent event, String operation) {
        if (updated != 1) {
            throw new ClaimLostException(
                    "Unable to " + operation + " shop projection event " + event.eventId()
                            + ": claim is no longer owned");
        }
    }

    public static final class ClaimLostException extends IllegalStateException {
        public ClaimLostException(String message) {
            super(message);
        }
    }

    public record ClaimedEvent(
            long id,
            String eventId,
            long shopId,
            long aggregateVersion,
            int attempts,
            String claimToken
    ) {
    }

    public record ProjectionChange(long id, long shopId, long aggregateVersion) {
    }
}
