package com.citycopilot.core.projection;

import com.citycopilot.core.entity.Shop;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 店铺搜索/GEO 投影 Outbox 仓储。
 *
 * <p>负责在店铺事务内追加版本化事件，并通过带 claim token 的抢占、完成和退避重试支持至少一次投影。</p>
 */
@Repository
public class ShopProjectionOutboxRepository {

    /** 执行 Outbox 与店铺版本更新 SQL。 */
    private final JdbcTemplate jdbc;

    /** 序列化便于诊断的 ShopChanged 事件载荷。 */
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc 数据库访问模板
     * @param objectMapper JSON 序列化器
     */
    public ShopProjectionOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 在调用方店铺事务内原子递增事实版本并追加 ShopChanged 事件。
     *
     * @param before 变更前店铺；创建时为空
     * @param after 变更后店铺，必须包含主键
     * @return 新的店铺投影版本
     */
    public long append(Shop before, Shop after) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("ShopChanged outbox append requires an active transaction");
        }
        if (after == null || after.getId() == null) {
            throw new IllegalArgumentException("after shop and id are required");
        }
        // 追加事件持有切换屏障共享锁；重建以排他锁关闭水位时，可精确区分切换前后已提交变更。
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

    /**
     * 按事件主键顺序抢占可处理或已过期的 Outbox 记录。
     *
     * <p>每条记录使用随机 claim token 做 CAS，多个 Projector 并发扫描也不会共同拥有同一事件。</p>
     *
     * @param requestedLimit 请求批量大小，内部限制在安全范围
     * @param owner 可诊断的抢占者名称
     * @param claimTtlMicros 抢占有效期，单位为微秒
     * @return 当前调用成功拥有的事件
     */
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

    /**
     * 将仍由当前 claim token 拥有的事件标记为已处理。
     *
     * @param event 已抢占事件
     */
    public void complete(ClaimedEvent event) {
        int updated = jdbc.update("""
                UPDATE shop_projection_outbox
                SET status='PROCESSED', processed_at=CURRENT_TIMESTAMP(3),
                    claim_token=NULL, claim_owner=NULL, claimed_until=NULL, last_error=NULL
                WHERE id=? AND claim_token=? AND status='CLAIMED'
                """, event.id(), event.claimToken());
        requireOwnedClaim(updated, event, "complete");
    }

    /**
     * 记录投影失败并按指数退避安排下一次尝试。
     *
     * @param event 已抢占事件
     * @param failure 本次失败
     * @param maxRetryDelay 最大重试延迟
     */
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

    /** @return 当前 Outbox 最大自增主键，用作重建追平水位 */
    public long highWatermark() {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM shop_projection_outbox", Long.class);
        return value == null ? 0L : value;
    }

    /**
     * 按主键升序读取指定水位区间内的投影变化摘要。
     *
     * @param exclusiveId 不包含的起始事件主键
     * @param inclusiveId 包含的结束事件主键
     * @param requestedLimit 最大返回条数
     * @return 版本追平所需的店铺变更
     */
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

    /**
     * 构造便于诊断和未来扩展的 ShopChanged JSON 载荷。
     *
     * @param before 变更前店铺
     * @param after 变更后店铺
     * @param version 新投影版本
     * @return JSON 事件载荷
     */
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
        } catch (JacksonException error) {
            throw new IllegalStateException("Unable to serialize ShopChanged", error);
        }
    }

    /**
     * 将失败原因限制在数据库列允许长度内。
     *
     * @param failure 投影异常
     * @return 最多 1024 字符的诊断文本
     */
    private String truncate(Throwable failure) {
        String value = failure == null || failure.getMessage() == null
                ? "unknown projection failure"
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return value.substring(0, Math.min(1024, value.length()));
    }

    /**
     * 计算从一秒开始、受上限约束的指数退避。
     *
     * @param attempts 已尝试次数
     * @param maxRetryDelay 配置的最大延迟
     * @return 下一次延迟秒数
     */
    private long retryDelaySeconds(int attempts, Duration maxRetryDelay) {
        long maximumSeconds = Math.max(1L, maxRetryDelay == null ? 900L : maxRetryDelay.toSeconds());
        int exponent = Math.min(30, Math.max(0, attempts - 1));
        return Math.min(maximumSeconds, 1L << exponent);
    }

    /**
     * 确认完成或失败更新仍命中当前 claim token。
     *
     * @param updated SQL 影响行数
     * @param event 目标事件
     * @param operation 操作名称，用于异常信息
     */
    private void requireOwnedClaim(int updated, ClaimedEvent event, String operation) {
        if (updated != 1) {
            throw new ClaimLostException(
                    "Unable to " + operation + " shop projection event " + event.eventId()
                            + ": claim is no longer owned");
        }
    }

    /** 处理结束前 claim 已过期或被其他 Projector 接管时抛出的异常。 */
    public static final class ClaimLostException extends IllegalStateException {
        public ClaimLostException(String message) {
            super(message);
        }
    }

    /**
     * 当前 Projector 已抢占的 Outbox 事件。
     *
     * @param id Outbox 自增主键
     * @param eventId 业务事件唯一标识
     * @param shopId 店铺主键
     * @param aggregateVersion 该事件要求达到的店铺投影版本
     * @param attempts 已抢占尝试次数
     * @param claimToken 本次抢占所有权令牌
     */
    public record ClaimedEvent(
            long id,
            String eventId,
            long shopId,
            long aggregateVersion,
            int attempts,
            String claimToken
    ) {
    }

    /**
     * 全量重建追平阶段使用的最小变更摘要。
     *
     * @param id Outbox 事件主键
     * @param shopId 店铺主键
     * @param aggregateVersion 事件对应的店铺投影版本
     */
    public record ProjectionChange(long id, long shopId, long aggregateVersion) {
    }
}
