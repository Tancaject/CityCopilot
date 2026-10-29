package com.citycopilot.commerce;

import com.citycopilot.contract.event.EventEnvelope;

import com.citycopilot.contract.event.StockDiscrepancyDetectedEvent;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int IDEMPOTENCY_DELETE_BATCH = 1000;
    private static final int PREPARED_RESOLVE_BATCH = 500;

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
    private final CommerceRedisKeyspace keyspace;
    /** 活动结束后继续对账的时长；超过后归档并释放 Redis 状态。 */
    private final long archiveAfterSeconds;
    /** 归档前把从未进入 Redis 的 PREPARED 准入固化为稳定拒绝。 */
    private final CommerceAdmissionDurabilityService durableAdmissions;
    /** 两次全量快照的最长间隔；期间每轮只做汇总数快速核对，不一致时立即转全量。 */
    private final long fullCheckNanos;
    /** 每张券上次全量快照的单调时钟时间；本实例接手巡检后首轮总是全量。 */
    private final Map<Long, Long> lastFullCheck = new ConcurrentHashMap<>();

    public CommerceReconciliationJob(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            CommerceConsistencyService consistency,
            CommerceRedisCampaignMetadata campaignMetadata,
            ObjectMapper objectMapper,
            CommerceJobLease leases,
            CommerceRedisKeyspace keyspace,
            @Value("${citycopilot.commerce.campaign-archive-after:1d}") Duration archiveAfter,
            CommerceAdmissionDurabilityService durableAdmissions,
            @Value("${citycopilot.commerce.reconciliation-full-interval:10m}") Duration fullCheckInterval
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.consistency = consistency;
        this.campaignMetadata = campaignMetadata;
        this.objectMapper = objectMapper;
        this.leases = leases;
        this.keyspace = keyspace;
        this.archiveAfterSeconds = archiveAfter.toSeconds();
        this.durableAdmissions = durableAdmissions;
        this.fullCheckNanos = fullCheckInterval.toNanos();
    }

    /** 巡检进行中或刚结束的 Voucher，并归档已结束超过保留期的 Voucher。 */
    @Scheduled(fixedDelayString = "${citycopilot.commerce.reconciliation-ms:5000}")
    void reconcile() {
        leases.acquire("stock-reconciliation").ifPresent(this::reconcile);
    }

    private void reconcile(CommerceJobLease.Lease lease) {
        // 只巡检进行中或刚结束的活动：已结束的历史活动不再参与准入，巡检成本不随历史累积增长。
        long afterId = 0;
        while (true) {
            List<Long> voucherIds = jdbc.queryForList("""
                    SELECT voucher_id FROM commerce_seckill_voucher
                    WHERE redis_archived_at IS NULL
                      AND end_time > CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND
                      AND voucher_id>?
                    ORDER BY voucher_id
                    LIMIT ?
                    """, Long.class, archiveAfterSeconds, afterId, SCAN_BATCH_SIZE);
            if (voucherIds.isEmpty()) break;
            for (Long voucherId : voucherIds) {
                runIsolated(lease, voucherId, () -> reconcileVoucher(voucherId));
                afterId = voucherId;
            }
        }
        List<Long> ended = jdbc.queryForList("""
                SELECT voucher_id FROM commerce_seckill_voucher
                WHERE redis_archived_at IS NULL
                  AND end_time <= CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND
                ORDER BY voucher_id
                LIMIT ?
                """, Long.class, archiveAfterSeconds, SCAN_BATCH_SIZE);
        for (Long voucherId : ended) {
            runIsolated(lease, voucherId, () -> archive(voucherId));
        }
    }

    /** 单张券失败不终止本轮；租约丢失则立即结束，避免旧写者继续操作。 */
    private void runIsolated(CommerceJobLease.Lease lease, long voucherId, Runnable work) {
        try {
            lease.runFenced(work);
        } catch (CommerceJobLease.LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException failure) {
            log.error("Commerce reconciliation failed for voucher {}; continuing with remaining vouchers",
                    voucherId, failure);
        }
    }

    /**
     * 归档已结束活动：确认没有待支付订单、未完成准入和待回补库存后标记归档，提交后删除 Redis 状态。
     *
     * <p>归档后的退款只回补 MySQL 库存，见 {@link CommerceOrderService#attemptTransitionInventoryRestore}。</p>
     */
    private void archive(long voucherId) {
        Long inflight = redis.opsForSet().size(keyspace.inflight(voucherId));
        if ((inflight != null && inflight > 0)
                || Boolean.TRUE.equals(redis.hasKey(keyspace.fence(voucherId)))
                || Boolean.TRUE.equals(redis.hasKey(keyspace.campaignUpdateFence(voucherId)))) {
            return;
        }
        rejectNeverAdmitted(voucherId);
        // 只需知道“有没有”，EXISTS 在第一行处停止；COUNT 每 5 秒要把每张待归档券的未结订单全数一遍。
        Boolean busy = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM commerce_order WHERE voucher_id=? AND status=1)
                    OR EXISTS(SELECT 1 FROM commerce_admission_ledger WHERE voucher_id=? AND status IN ('PREPARED','ACCEPTED'))
                    OR EXISTS(SELECT 1 FROM commerce_order_recovery WHERE voucher_id=? AND terminal_at IS NULL)
                """, Boolean.class, voucherId, voucherId, voucherId);
        if (busy == null || busy) return;
        int marked = jdbc.update("""
                UPDATE commerce_seckill_voucher SET redis_archived_at=CURRENT_TIMESTAMP(3)
                WHERE voucher_id=? AND redis_archived_at IS NULL
                """, voucherId);
        if (marked != 1) return;
        List<String> keys = List.of(keyspace.campaignMeta(voucherId), keyspace.stock(voucherId),
                keyspace.buyers(voucherId), keyspace.inflight(voucherId));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lastFullCheck.remove(voucherId);
                redis.delete(keys);
                redis.delete("commerce:metrics:seckill:discrepancy-streak:" + voucherId);
                deleteIdempotencyKeys(voucherId);
            }
        });
    }

    /**
     * 活动结束超过归档窗口后 Lua 已不可能再准入该券。没有维护 fence、也没有在途消息时，PREPARED 行在 Redis
     * 中若没有券内幂等身份，说明它从未被准入，按“活动已结束”固化为稳定拒绝，与此时同键重试得到的结果相同；
     * 否则这些行会永远阻止归档。Redis 中已有身份的行仍交给请求重试与 Worker，后台不把结果未知改为已接受。
     * 其他路由代际的行写在另一组键里，这里查不到也不能据此判断，一律跳过。处理在租约事务内进行，每轮最多
     * {@value #PREPARED_RESOLVE_BATCH} 行，剩余的留给下一轮，避免长事务一直占着租约行锁。
     */
    private void rejectNeverAdmitted(long voucherId) {
        CommerceApiException ended = new CommerceApiException("SECKILL_ENDED", "Seckill has ended", 409);
        for (CommerceAdmissionDurabilityService.Admission prepared
                : durableAdmissions.preparedFor(voucherId, PREPARED_RESOLVE_BATCH)) {
            if (!prepared.matchesRouting(keyspace.routingGeneration(), keyspace.physicalShardCount())) continue;
            String identity = keyspace.idempotency(voucherId, prepared.userId(), prepared.idempotencyKey());
            if (Boolean.TRUE.equals(redis.hasKey(identity))) continue;
            durableAdmissions.markRejected(prepared, ended);
        }
    }

    /**
     * 每次准入都会在券内写一个幂等身份键；归档后不再有 Lua 准入，按账本中的用户幂等键分批删除，
     * 否则这些键随订单量永久累积直到 Redis 写满。
     */
    private void deleteIdempotencyKeys(long voucherId) {
        List<String> batch = new ArrayList<>(IDEMPOTENCY_DELETE_BATCH);
        jdbc.query("SELECT user_id,idempotency_key FROM commerce_admission_ledger WHERE voucher_id=?", row -> {
            batch.add(keyspace.idempotency(voucherId, row.getLong("user_id"), row.getString("idempotency_key")));
            if (batch.size() == IDEMPOTENCY_DELETE_BATCH) {
                redis.unlink(batch);
                batch.clear();
            }
        }, voucherId);
        if (!batch.isEmpty()) redis.unlink(batch);
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
        String streakKey = "commerce:metrics:seckill:discrepancy-streak:" + voucherId;
        // 全量快照要取出整张券的买家名单逐个比对，成本随买家数线性增长；平时只比汇总数，
        // 汇总数不一致或到了全量间隔才做全量，维护 fence 与告警仍只由全量结果决定。
        long now = System.nanoTime();
        Long lastFull = lastFullCheck.get(voucherId);
        if (lastFull != null && now - lastFull < fullCheckNanos && consistency.quickConsistent(voucherId)) {
            redis.delete(streakKey);
            return;
        }
        Map<String, Object> snapshot = consistency.snapshotForReconciliation(voucherId);
        lastFullCheck.put(voucherId, now);
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
            // 先持久化 Outbox，再由独立 Relay 发布；Kafka 暂时不可用不会丢失告警事件。
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
