package com.citycopilot.commerce;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Redis authority used by the synchronous seckill admission path. */
@Component
final class CommerceRedisCampaignMetadata {
    private static final Logger log = LoggerFactory.getLogger(CommerceRedisCampaignMetadata.class);
    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT =
            script("lua/commerce_invalidate_campaign.lua");
    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT =
            script("lua/commerce_publish_campaign.lua");
    private static final DefaultRedisScript<Long> REMOVE_SCRIPT =
            script("lua/commerce_remove_campaign.lua");

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final CommerceRedisKeyspace keyspace;
    private final boolean bootstrapEnabled;
    private final long updateFenceTtlMillis;

    CommerceRedisCampaignMetadata(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            CommerceRedisKeyspace keyspace,
            @Value("${citycopilot.commerce.inventory-bootstrap-enabled:true}") boolean bootstrapEnabled,
            @Value("${citycopilot.commerce.metadata-update-fence-ttl-ms:300000}")
            long updateFenceTtlMillis
    ) {
        if (updateFenceTtlMillis < 1000L) {
            throw new IllegalArgumentException("Commerce metadata update fence TTL must be at least 1000 ms");
        }
        this.jdbc = jdbc;
        this.redis = redis;
        this.keyspace = keyspace;
        this.bootstrapEnabled = bootstrapEnabled;
        this.updateFenceTtlMillis = updateFenceTtlMillis;
    }

    @PostConstruct
    void initializeMetadata() {
        if (!bootstrapEnabled) return;
        for (Map<String, Object> voucher : jdbc.queryForList(metadataQuery())) {
            long voucherId = ((Number) voucher.get("id")).longValue();
            write(voucher, currentRevision(voucherId), RefreshMode.RECONCILE);
        }
    }

    long invalidateBeforeChange(long voucherId) {
        Long revision = redis.execute(
                INVALIDATE_SCRIPT,
                List.of(
                        keyspace.campaignRevision(voucherId),
                        keyspace.campaignMeta(voucherId),
                        keyspace.campaignUpdateFence(voucherId)
                ),
                String.valueOf(updateFenceTtlMillis)
        );
        if (revision == null || revision <= 0) {
            throw new IllegalStateException("Unable to invalidate Commerce campaign metadata");
        }
        return revision;
    }

    void refreshAfterCommit(long voucherId) {
        Long expectedRevision = null;
        try {
            expectedRevision = currentRevision(voucherId);
            refreshFromDatabase(voucherId, expectedRevision, RefreshMode.RECONCILE);
        } catch (RuntimeException failure) {
            cleanupIfUnchanged(voucherId, expectedRevision, RefreshMode.RECONCILE, failure);
            logRefreshFailure(voucherId, failure);
        }
    }

    void refreshAfterCommit(long voucherId, long expectedRevision) {
        try {
            refreshFromDatabase(voucherId, expectedRevision, RefreshMode.COMMIT);
        } catch (RuntimeException failure) {
            cleanupIfUnchanged(voucherId, expectedRevision, RefreshMode.COMMIT, failure);
            logRefreshFailure(voucherId, failure);
        }
    }

    void refreshFromDatabase(long voucherId) {
        refreshFromDatabase(voucherId, currentRevision(voucherId), RefreshMode.RECONCILE);
    }

    private void refreshFromDatabase(
            long voucherId,
            long expectedRevision,
            RefreshMode mode
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList(metadataQuery() + " WHERE v.id=?", voucherId);
        if (rows.isEmpty()) {
            remove(voucherId, expectedRevision, mode);
            return;
        }
        write(rows.get(0), expectedRevision, mode);
    }

    private void write(Map<String, Object> voucher, long expectedRevision, RefreshMode mode) {
        long voucherId = ((Number) voucher.get("id")).longValue();
        int type = ((Number) voucher.get("type")).intValue();
        Long beginEpochMillis = epochMillis(voucher.get("begin_time"));
        Long endEpochMillis = epochMillis(voucher.get("end_time"));
        if (type != 1 || beginEpochMillis == null || endEpochMillis == null) {
            remove(voucherId, expectedRevision, mode);
            return;
        }
        Long published = redis.execute(
                PUBLISH_SCRIPT,
                List.of(
                        keyspace.campaignRevision(voucherId),
                        keyspace.campaignMeta(voucherId),
                        keyspace.campaignUpdateFence(voucherId)
                ),
                String.valueOf(expectedRevision),
                String.valueOf(voucherId),
                String.valueOf(type),
                String.valueOf(((Number) voucher.get("status")).intValue()),
                String.valueOf(beginEpochMillis),
                String.valueOf(endEpochMillis),
                ((Number) voucher.get("voucher_version")).longValue()
                        + ":"
                        + ((Number) voucher.get("seckill_version")).longValue(),
                mode.name()
        );
        if (published == null) {
            throw new IllegalStateException("Redis did not return a campaign publication result");
        }
        // Zero means a newer invalidation won. Leaving its key absent is the
        // required fail-closed result; an older transaction must not reopen it.
    }

    private long currentRevision(long voucherId) {
        String raw = redis.opsForValue().get(keyspace.campaignRevision(voucherId));
        if (raw == null) return 0L;
        try {
            long revision = Long.parseLong(raw);
            if (revision < 0) throw new NumberFormatException("negative revision");
            return revision;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("Commerce campaign revision is invalid", invalid);
        }
    }

    private void remove(long voucherId, long expectedRevision, RefreshMode mode) {
        Long removed = redis.execute(
                REMOVE_SCRIPT,
                List.of(
                        keyspace.campaignRevision(voucherId),
                        keyspace.campaignMeta(voucherId),
                        keyspace.campaignUpdateFence(voucherId)
                ),
                String.valueOf(expectedRevision),
                mode.name()
        );
        if (removed == null) {
            throw new IllegalStateException("Redis did not return a campaign removal result");
        }
    }

    private void cleanupIfUnchanged(
            long voucherId,
            Long expectedRevision,
            RefreshMode mode,
            RuntimeException failure
    ) {
        if (expectedRevision == null) return;
        try {
            remove(voucherId, expectedRevision, mode);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void logRefreshFailure(long voucherId, RuntimeException failure) {
        log.error(
                "Unable to refresh Redis campaign metadata for voucher {}; admission remains closed",
                voucherId,
                failure
        );
    }

    private String metadataQuery() {
        return """
                SELECT v.id,v.type,v.status,v.version AS voucher_version,
                       s.version AS seckill_version,s.begin_time,s.end_time
                FROM commerce_voucher v
                LEFT JOIN commerce_seckill_voucher s ON s.voucher_id=v.id
                """;
    }

    private Long epochMillis(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toEpochMilli();
        if (value instanceof Instant instant) return instant.toEpochMilli();
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        return null;
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private enum RefreshMode {
        COMMIT,
        RECONCILE
    }
}
