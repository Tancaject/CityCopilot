package com.citycopilot.commerce;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * 维护同步秒杀准入所读取的 Redis 活动权威副本。
 *
 * <p>MySQL 保存业务事实，Redis Hash 提供热路径校验。管理更新前先使副本失效，事务提交后再按
 * 修订号发布；任何刷新失败都保留“键缺失”状态，使准入失败关闭而不是读取旧活动配置。</p>
 */
@Component
final class CommerceRedisCampaignMetadata {
    private static final Logger log = LoggerFactory.getLogger(CommerceRedisCampaignMetadata.class);
    private static final int SCAN_BATCH_SIZE = 200;
    /** 原子递增修订号、删除旧元数据并建立更新 fence 的 Lua。 */
    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_invalidate_campaign.lua");
    /** 仅在修订号仍匹配时发布活动元数据的 Lua。 */
    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_publish_campaign.lua");
    /** 仅由当前修订所有者删除元数据的 Lua。 */
    private static final DefaultRedisScript<Long> REMOVE_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_remove_campaign.lua");

    /** 从 MySQL 读取 Voucher 和秒杀时间窗。 */
    private final JdbcTemplate jdbc;
    /** 执行带修订校验的元数据 Lua。 */
    private final StringRedisTemplate redis;
    /** 生成与 Voucher 库存同槽的元数据键。 */
    private final CommerceRedisKeyspace keyspace;
    /** 是否在启动时从 MySQL 重建活动元数据。 */
    private final boolean bootstrapEnabled;
    /** 管理更新 fence 的生存时间，防止异常流程永久封锁。 */
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

    /** 启动时按数据库当前值协调全部活动元数据；旧提交不能覆盖较新的修订号。 */
    @PostConstruct
    void initializeMetadata() {
        if (!bootstrapEnabled) return;
        Long highWatermark = jdbc.queryForObject("SELECT MAX(id) FROM commerce_voucher", Long.class);
        if (highWatermark == null) return;
        long afterId = 0;
        while (afterId < highWatermark) {
            List<Map<String, Object>> vouchers = jdbc.queryForList(metadataQuery()
                    + " WHERE v.id>? AND v.id<=? ORDER BY v.id LIMIT ?", afterId, highWatermark, SCAN_BATCH_SIZE);
            if (vouchers.isEmpty()) break;
            for (Map<String, Object> voucher : vouchers) {
                long voucherId = ((Number) voucher.get("id")).longValue();
                write(voucher, currentRevision(voucherId), RefreshMode.RECONCILE);
                afterId = voucherId;
            }
        }
    }

    /**
     * 在修改 MySQL 前关闭指定活动准入，并取得本次更新拥有的修订号。
     *
     * @param voucherId Voucher ID
     * @return 事务提交后发布元数据时必须携带的修订号
     */
    long invalidateBeforeChange(long voucherId) {
        // 三个键同槽，由 Lua 一次完成修订递增、元数据删除和 fence 建立，不暴露半更新状态。
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

    /**
     * 协调任务使用：读取当前修订号并从 MySQL 重发元数据；失败时继续保持准入关闭。
     */
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

    /**
     * 管理事务提交后，使用更新前取得的修订号发布新元数据。
     *
     * @param voucherId Voucher ID
     * @param expectedRevision 本次更新拥有的修订号
     */
    void refreshAfterCommit(long voucherId, long expectedRevision) {
        try {
            refreshFromDatabase(voucherId, expectedRevision, RefreshMode.COMMIT);
        } catch (RuntimeException failure) {
            cleanupIfUnchanged(voucherId, expectedRevision, RefreshMode.COMMIT, failure);
            logRefreshFailure(voucherId, failure);
        }
    }

    /** 从 MySQL 重新加载指定 Voucher，并按当前修订号协调 Redis。 */
    void refreshFromDatabase(long voucherId) {
        refreshFromDatabase(voucherId, currentRevision(voucherId), RefreshMode.RECONCILE);
    }

    /**
     * 根据数据库结果选择发布或删除；不存在的 Voucher 不得在 Redis 留下可准入元数据。
     */
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

    /**
     * 将秒杀活动字段按修订号原子发布；普通 Voucher 或不完整时间窗会改走删除流程。
     */
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
        // 返回 0 表示较新的失效操作已经获胜；此时必须保留键缺失，旧事务不能重新开放准入。
    }

    /** @return 当前活动修订号；键尚未建立时返回 0 */
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

    /** 仅在修订所有权仍匹配时删除活动元数据并处理对应 fence。 */
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

    /** 刷新失败后尽力删除自己版本的元数据；清理失败作为 suppressed 异常保留。 */
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

    /** 区分事务提交后的发布与后台协调，Lua 据此执行相应的修订/fence 规则。 */
    private enum RefreshMode {
        /** 使用管理更新前获得的修订号完成提交后发布。 */
        COMMIT,
        /** 后台按数据库现状协调，不声明某个管理事务所有权。 */
        RECONCILE
    }
}
