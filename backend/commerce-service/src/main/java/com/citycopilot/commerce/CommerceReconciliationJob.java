package com.citycopilot.commerce;

import com.citycopilot.contract.event.EventEnvelope;

import com.citycopilot.contract.event.StockDiscrepancyDetectedEvent;
import tools.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 周期性核对 MySQL 与 Redis 的秒杀状态，并将持续异常写入 Outbox。
 *
 * <p>任务只负责发现、隔离和告警，不直接猜测正确库存；实际修复由带证据哈希的运维接口完成。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.commerce",
        name = "reconciliation-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CommerceReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(CommerceReconciliationJob.class);
    private static final int SCAN_BATCH_SIZE = 200;

    /** 查询需要巡检的 Voucher，并写入异常事件 Outbox。 */
    private final JdbcTemplate jdbc;
    /** 维护异常连续次数、告警冷却键等短期状态。 */
    private final StringRedisTemplate redis;
    /** 生成一致性快照并在必要时建立准入 fence。 */
    private final CommerceConsistencyService consistency;
    /** 重新发布数据库中的活动元数据，修复提交后刷新失败。 */
    private final CommerceRedisCampaignMetadata campaignMetadata;
    /** 序列化 Outbox 事件信封。 */
    private final ObjectMapper objectMapper;
    private final CommerceJobLease leases;

    public CommerceReconciliationJob(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            CommerceConsistencyService consistency,
            CommerceRedisCampaignMetadata campaignMetadata,
            ObjectMapper objectMapper,
            CommerceJobLease leases
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.consistency = consistency;
        this.campaignMetadata = campaignMetadata;
        this.objectMapper = objectMapper;
        this.leases = leases;
    }

    /** 按主键分批扫描本轮上界内的 Voucher；单个失败不终止后续巡检。 */
    @Scheduled(fixedDelayString = "${citycopilot.commerce.reconciliation-ms:5000}")
    void reconcile() {
        leases.acquire("stock-reconciliation").ifPresent(this::reconcile);
    }

    private void reconcile(CommerceJobLease.Lease lease) {
        Long highWatermark = jdbc.queryForObject("SELECT MAX(voucher_id) FROM commerce_seckill_voucher", Long.class);
        if (highWatermark == null) return;
        long afterId = 0;
        while (afterId < highWatermark) {
            List<Long> voucherIds = jdbc.queryForList("""
                    SELECT voucher_id FROM commerce_seckill_voucher
                    WHERE voucher_id>? AND voucher_id<=?
                    ORDER BY voucher_id
                    LIMIT ?
                    """, Long.class, afterId, highWatermark, SCAN_BATCH_SIZE);
            if (voucherIds.isEmpty()) break;
            for (Long voucherId : voucherIds) {
                try {
                    lease.runFenced(() -> reconcileVoucher(voucherId));
                } catch (CommerceJobLease.LeaseLostException lost) {
                    throw lost;
                } catch (RuntimeException failure) {
                    log.error(
                            "Commerce reconciliation failed for voucher {}; continuing with remaining vouchers",
                            voucherId,
                            failure
                    );
                }
                afterId = voucherId;
            }
        }
    }

    /**
     * 刷新元数据、采集一致性证据，并在异常持续时产生去重告警。
     *
     * @param voucherId 待巡检的 Voucher ID
     */
    private void reconcileVoucher(long voucherId) {
        // 以数据库为准重发元数据，可修复管理更新回滚或 afterCommit 刷新失败；
        // 刷新异常会删除元数据键，使准入继续失败关闭。
        campaignMetadata.refreshAfterCommit(voucherId);
        Map<String, Object> snapshot = consistency.snapshotForReconciliation(voucherId);
        String streakKey = "commerce:metrics:seckill:discrepancy-streak:" + voucherId;
        if (Boolean.TRUE.equals(snapshot.get("consistent"))) {
            redis.delete(streakKey);
            return;
        }
        if (Boolean.TRUE.equals(snapshot.get("reconciliationFenceRequired"))) {
            // 静默期一旦发现库存或买家集合不一致就先封住新准入；告警仍按连续次数去重，安全隔离不等待告警发布。
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

    /**
     * 将库存差异封装为领域事件并写入 Outbox，交由 Relay 异步发布到运维主题。
     *
     * @param voucherId 异常 Voucher ID
     * @param snapshot 一致性快照
     * @param incidentKey 本轮事件的稳定关联键
     */
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
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(
                eventId, StockDiscrepancyDetectedEvent.EVENT_TYPE, StockDiscrepancyDetectedEvent.SCHEMA_VERSION,
                "commerce_seckill_voucher", String.valueOf(voucherId), now.toEpochMilli(), "commerce-service",
                now.toString(), incidentKey, payload);
        try {
            // 先持久化 Outbox，再由独立 Relay 发布；RocketMQ 暂时不可用不会丢失告警事件。
            jdbc.update("""
                    INSERT INTO commerce_outbox_event(
                        event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                        topic,payload_json,status
                    ) VALUES (?,?,?,?,?,'citycopilot-ops-events-v1',?,'READY')
                    """,
                    eventId,
                    envelope.eventType(),
                    envelope.aggregateType(),
                    envelope.aggregateId(),
                    envelope.aggregateVersion(),
                    objectMapper.writeValueAsString(envelope)
            );
        } catch (Exception error) {
            throw new IllegalStateException("Unable to append commerce discrepancy event", error);
        }
    }
}
