package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 比对 MySQL 事实与 Redis 秒杀库存/买家集合，并提供带证据校验的受控修复。
 *
 * <p>修复前必须确认无在途准入、无冲突预留、无进行中的恢复，再取得库存 fence；调用方提交的
 * evidenceHash 充当乐观并发条件，防止用过期快照覆盖新业务结果。</p>
 */
@Service
public class CommerceConsistencyService {
    private static final Logger log = LoggerFactory.getLogger(CommerceConsistencyService.class);
    /** 原子重建库存和买家集合且校验 repair fence 所有权。 */
    private static final DefaultRedisScript<Long> REPAIR_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_repair_inventory.lua");
    /** 仅由令牌持有者删除库存 fence。 */
    private static final DefaultRedisScript<Long> RELEASE_FENCE_SCRIPT =
            CommerceRedisScripts.longScript("lua/commerce_release_fence.lua");
    /** 后台巡检建立的固定 fence 所有者标识。 */
    static final String RECONCILIATION_FENCE_TOKEN = "commerce-reconciliation:v1";
    /** 人工修复 fence 的令牌前缀，后接每次修复的唯一标识。 */
    private static final String REPAIR_FENCE_TOKEN_PREFIX = "commerce-repair:v1:";

    /** 读取数据库库存、订单、预留及恢复状态。 */
    private final JdbcTemplate jdbc;
    /** 读取 Redis 库存/集合/Stream，并执行修复 Lua。 */
    private final StringRedisTemplate redis;
    /** 将规范化证据序列化后计算稳定哈希。 */
    private final ObjectMapper objectMapper;
    /** 生成当前路由代际的同槽键。 */
    private final CommerceRedisKeyspace keyspace;
    /** 对关键 fence 写执行 Redis 副本确认；测试构造可为空。 */
    private final CommerceRedisReplicationExecutor replicatedRedis;
    /** 让修复与路由迁移互斥；测试构造可为空。 */
    private final CommerceRuntimeMutationLeases runtimeLeases;
    /** 修复前确认当前路由仍允许业务写。 */
    private final CommerceRoutingGuard routing;

    @Autowired
    public CommerceConsistencyService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            CommerceRedisKeyspace keyspace,
            CommerceRedisReplicationExecutor replicatedRedis,
            CommerceRuntimeMutationLeases runtimeLeases,
            CommerceRoutingGuard routing
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keyspace = keyspace;
        this.replicatedRedis = replicatedRedis;
        this.runtimeLeases = runtimeLeases;
        this.routing = routing;
    }

    CommerceConsistencyService(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(jdbc, redis, objectMapper, new CommerceRedisKeyspace(1, "0"), null, null, null);
    }

    /**
     * 采集指定 Voucher 的完整一致性证据，并附全局 Stream 诊断。
     *
     * @param voucherId Voucher ID
     * @return 可供运维查看和后续 CAS 修复使用的证据
     */
    public Map<String, Object> snapshot(long voucherId) {
        return capture(voucherId, true).evidence();
    }

    /** 后台巡检快照，不加入与该 Voucher 无关的全局 Stream 信息。 */
    Map<String, Object> snapshotForReconciliation(long voucherId) {
        return capture(voucherId, false).evidence();
    }

    /**
     * 从两套存储重建期望状态，并在加入时间戳前计算 evidenceHash。
     *
     * <p>期望 Redis 库存 = MySQL 可用库存 - 尚未生成订单的有效 RESERVED 预留。</p>
     */
    private ConsistencyCapture capture(long voucherId, boolean includeGlobalDiagnostics) {
        Integer databaseStock = jdbc.queryForObject(
                "SELECT stock FROM commerce_seckill_voucher WHERE voucher_id=?",
                Integer.class,
                voucherId
        );
        String raw = redis.opsForValue().get(keyspace.stock(voucherId));
        Integer redisStock = parseStock(raw);
        List<Long> databaseBuyerIds = jdbc.queryForList("""
                SELECT user_id FROM commerce_order WHERE voucher_id=?
                UNION
                SELECT user_id FROM commerce_reservation
                WHERE voucher_id=? AND status='RESERVED'
                ORDER BY user_id
                """, Long.class, voucherId, voucherId);
        List<String> databaseBuyers = sortedStrings(databaseBuyerIds);
        Set<String> rawRedisBuyers = redis.opsForSet().members(
                keyspace.buyers(voucherId)
        );
        boolean redisBuyerSetAvailable = rawRedisBuyers != null;
        List<String> redisBuyers = sortedStrings(
                rawRedisBuyers == null ? List.of() : rawRedisBuyers
        );
        List<String> missingRedisBuyers = difference(databaseBuyers, redisBuyers);
        List<String> unexpectedRedisBuyers = difference(redisBuyers, databaseBuyers);
        Long voucherInFlight = redis.opsForSet().size(
                keyspace.inflight(voucherId)
        );
        List<Map<String, Object>> unresolved = jdbc.queryForList("""
                SELECT r.order_id,r.status,
                       CASE WHEN o.id IS NULL THEN 0 ELSE 1 END order_exists
                FROM commerce_reservation r
                LEFT JOIN commerce_order o ON o.id=r.order_id
                WHERE r.voucher_id=? AND r.status IN ('RESERVED','RELEASE_REQUIRED','ORDER_CREATED')
                ORDER BY r.order_id
                """, voucherId);
        List<Map<String, Object>> recoveryRows = jdbc.queryForList("""
                SELECT source_id,source_type,order_id,user_id,phase,compensation_delta
                FROM commerce_order_recovery
                WHERE voucher_id=?
                  AND phase IN (
                    'RECEIVED','COMPENSATION_REQUIRED','RESTORE_PENDING','REPAIR_REQUIRED'
                  )
                ORDER BY source_id
                """, voucherId);
        List<Map<String, Object>> repairRequiredRecovery = recoveryRows.stream()
                .filter(row -> "REPAIR_REQUIRED".equals(String.valueOf(row.get("phase"))))
                .toList();
        List<Map<String, Object>> activeRecovery = recoveryRows.stream()
                .filter(row -> !"REPAIR_REQUIRED".equals(String.valueOf(row.get("phase"))))
                .toList();
        int repairRequiredCompensationDelta = repairRequiredRecovery.stream()
                .map(row -> row.get("compensation_delta"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToInt(Number::intValue)
                .sum();
        int consuming = 0;
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> row : unresolved) {
            String status = String.valueOf(row.get("status"));
            boolean orderExists = ((Number) row.get("order_exists")).intValue() == 1;
            if ("ORDER_CREATED".equals(status) && orderExists) continue;
            if ("RESERVED".equals(status) && !orderExists) {
                consuming++;
                continue;
            }
            conflicts.add(row);
        }
        int expected = databaseStock - consuming;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("voucherId", voucherId);
        evidence.put("databaseStock", databaseStock);
        evidence.put("redisStock", redisStock);
        evidence.put("expectedRedisStock", expected);
        evidence.put("databaseBuyerCount", databaseBuyers.size());
        evidence.put("redisBuyerCount", redisBuyerSetAvailable ? redisBuyers.size() : null);
        evidence.put("databaseBuyerHash", hash(databaseBuyers));
        evidence.put("redisBuyerHash", redisBuyerSetAvailable ? hash(redisBuyers) : null);
        evidence.put("missingRedisBuyers", missingRedisBuyers);
        evidence.put("unexpectedRedisBuyers", unexpectedRedisBuyers);
        evidence.put("redisBuyerSetAvailable", redisBuyerSetAvailable);
        evidence.put("voucherInFlight", voucherInFlight);
        evidence.put("consumingReservations", consuming);
        evidence.put("reservationRowsInspected", unresolved.size());
        evidence.put("conflicts", conflicts);
        evidence.put("repairRequiredRecoveryCount", repairRequiredRecovery.size());
        evidence.put("repairRequiredCompensationDelta", repairRequiredCompensationDelta);
        evidence.put("repairRequiredRecoveryRows", repairRequiredRecovery);
        evidence.put("activeRecoveryCount", activeRecovery.size());
        evidence.put("activeRecoveryRows", activeRecovery);
        boolean stockBuyerConsistent = redisStock != null
                && redisStock == expected
                && redisBuyerSetAvailable
                && missingRedisBuyers.isEmpty()
                && unexpectedRedisBuyers.isEmpty();
        boolean inventoryConsistent = stockBuyerConsistent
                && voucherInFlight != null
                && voucherInFlight == 0L
                && conflicts.isEmpty();
        evidence.put("stockBuyerConsistent", stockBuyerConsistent);
        evidence.put(
                "reconciliationFenceRequired",
                !stockBuyerConsistent && voucherInFlight != null && voucherInFlight == 0L
        );
        evidence.put("inventoryConsistent", inventoryConsistent);
        evidence.put(
                "consistent",
                inventoryConsistent && recoveryRows.isEmpty()
        );
        evidence.put("ownerService", "commerce-service");
        String evidenceHash = hash(evidence);

        if (includeGlobalDiagnostics) {
            // 共享 Stream 仅用于排障，不是单个 Voucher 的事实，不能参与一致性判断或修复 CAS 哈希。
            evidence.put("globalStreamHealth", globalStreamHealth());
        }
        evidence.put("collectedAt", Instant.now().toString());
        evidence.put("evidenceHash", evidenceHash);
        return new ConsistencyCapture(evidence, List.copyOf(databaseBuyerIds));
    }

    /**
     * 在运行时写租约保护下执行证据化修复，并在返回前再次确认租约。
     *
     * @param voucherId Voucher ID
     * @param evidenceHash 调用方刚读取的证据哈希
     * @return 修复目标和前后证据摘要
     */
    public ConsistencyRepairResult repair(long voucherId, String evidenceHash) {
        if (runtimeLeases == null) return repairWithLease(voucherId, evidenceHash, null);
        try (CommerceRuntimeMutationLeases.MutationLease lease = runtimeLeases.acquireMutation()) {
            routing.requireRuntimeMutationActive();
            ConsistencyRepairResult result = repairWithLease(voucherId, evidenceHash, lease);
            lease.renewAndAssert();
            return result;
        }
    }

    /**
     * 后台发现静默期差异时建立固定库存 fence，立即阻止新准入。
     *
     * @param voucherId 异常 Voucher ID
     */
    void ensureReconciliationFence(long voucherId) {
        String fenceKey = keyspace.fence(voucherId);
        if (replicatedRedis == null) {
            redis.opsForValue().setIfAbsent(fenceKey, RECONCILIATION_FENCE_TOKEN);
            return;
        }
        replicatedRedis.setIfAbsent(
                "reconciliation_inventory_fence",
                fenceKey,
                RECONCILIATION_FENCE_TOKEN
        );
    }

    /**
     * 执行修复主流程：取得 fence、重验快照、Lua 重建、终态化恢复行、验证后解 fence。
     */
    private ConsistencyRepairResult repairWithLease(
            long voucherId,
            String evidenceHash,
            CommerceRuntimeMutationLeases.MutationLease runtimeLease
    ) {
        RepairFence repairFence = acquireRepairFence(voucherId, evidenceHash);
        boolean repairCompleted = false;
        try {
            ConsistencyCapture capture = capture(voucherId, false);
            Map<String, Object> before = capture.evidence();
            requireEvidenceHash(before, evidenceHash);
            if (before.get("voucherInFlight") == null) {
                throw new CommerceApiException(
                        "COMMERCE_INFLIGHT_LEDGER_UNAVAILABLE",
                        "Voucher in-flight ledger is unavailable",
                        503
                );
            }
            if (((Number) before.get("voucherInFlight")).longValue() != 0L) {
                throw new CommerceApiException(
                        "COMMERCE_VOUCHER_IN_FLIGHT",
                        "Voucher still has admitted orders in flight",
                        409
                );
            }
            if (!((List<?>) before.get("conflicts")).isEmpty()) {
                throw new CommerceApiException(
                        "COMMERCE_RESERVATION_CONFLICT",
                        "Reservation conflict blocks repair",
                        409
                );
            }
            if (!((List<?>) before.get("activeRecoveryRows")).isEmpty()) {
                throw new CommerceApiException(
                        "COMMERCE_RECOVERY_IN_PROGRESS",
                        "Voucher recovery must reach a repairable terminal phase first",
                        409
                );
            }
            int target = ((Number) before.get("expectedRedisStock")).intValue();
            List<Long> buyers = capture.databaseBuyerIds();
            List<String> arguments = new ArrayList<>();
            arguments.add(repairFence.token());
            arguments.add(String.valueOf(target));
            buyers.forEach(userId -> arguments.add(String.valueOf(userId)));
            renewAndAssert(runtimeLease);
            // Lua 同时校验 fence 令牌并替换库存/买家集合，不向准入暴露半修复状态。
            Long repaired = redis.execute(
                    REPAIR_SCRIPT,
                    List.of(
                            repairFence.key(),
                            keyspace.stock(voucherId),
                            keyspace.buyers(voucherId)
                    ),
                    arguments.toArray()
            );
            if (repaired == null || repaired < 0) {
                throw new CommerceApiException(
                        "COMMERCE_STALE_EVIDENCE",
                        "Inventory repair fence ownership changed before publication",
                        409
                );
            }
            Map<String, Object> repairedInventory = capture(voucherId, false).evidence();
            if (!Boolean.TRUE.equals(repairedInventory.get("inventoryConsistent"))) {
                throw new CommerceApiException(
                        "COMMERCE_REPAIR_VERIFICATION_FAILED",
                        "Inventory repair could not be verified",
                        409
                );
            }
            renewAndAssert(runtimeLease);
            // 只有 Redis 库存已验证后，才能把等待人工修复的恢复行标为 RESTORED。
            int restoredRecoveryRows = jdbc.update("""
                    UPDATE commerce_order_recovery
                    SET phase='RESTORED', compensation_delta=0,
                        finalized_at=COALESCE(finalized_at,CURRENT_TIMESTAMP(3))
                    WHERE voucher_id=? AND phase='REPAIR_REQUIRED'
                    """, voucherId);
            Map<String, Object> after = capture(voucherId, false).evidence();
            if (!Boolean.TRUE.equals(after.get("consistent"))) {
                throw new CommerceApiException(
                        "COMMERCE_REPAIR_VERIFICATION_FAILED",
                        "Inventory repair could not be verified",
                        409
                );
            }
            renewAndAssert(runtimeLease);
            releaseVerifiedRepairFence(repairFence);
            repairCompleted = true;
            return new ConsistencyRepairResult(voucherId, target, buyers.size(), restoredRecoveryRows,
                    evidenceHash, (String) after.get("evidenceHash"), true);
        } finally {
            if (!repairCompleted) {
                retainAutomaticFence(repairFence);
            }
        }
    }

    /**
     * 获取人工修复 fence；若已有巡检 fence，则在两次证据校验之间通过 CAS 接管。
     */
    private RepairFence acquireRepairFence(long voucherId, String evidenceHash) {
        String fenceKey = keyspace.fence(voucherId);
        String repairToken = REPAIR_FENCE_TOKEN_PREFIX + UUID.randomUUID();
        if (replicatedRedis == null) {
            Boolean acquired = redis.opsForValue().setIfAbsent(fenceKey, repairToken);
            if (Boolean.TRUE.equals(acquired)) {
                return new RepairFence(fenceKey, repairToken);
            }
        } else {
            CommerceRedisReplicationExecutor.ReplicatedMutation acquired;
            try {
                acquired = replicatedRedis.setIfAbsent(
                        "consistency_repair_fence",
                        fenceKey,
                        repairToken
                );
            } catch (RuntimeException failure) {
                retainAutomaticFence(new RepairFence(fenceKey, repairToken));
                throw failure;
            }
            if (acquired != CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT) {
                return new RepairFence(fenceKey, repairToken);
            }
        }

        String currentOwner = redis.opsForValue().get(fenceKey);
        if (!RECONCILIATION_FENCE_TOKEN.equals(currentOwner)) {
            throw inventoryMaintenance();
        }

        // 自动 fence 已封住新准入；接管前校验哈希，接管后的主流程再校验一次，
        // 从而覆盖交接期间 Worker 或恢复任务推进状态的竞态。
        requireEvidenceHash(capture(voucherId, false).evidence(), evidenceHash);
        if (replicatedRedis == null) {
            throw inventoryMaintenance();
        }
        CommerceRedisReplicationExecutor.ReplicatedMutation takeover;
        try {
            takeover = replicatedRedis.compareAndSet(
                    "consistency_repair_takeover",
                    fenceKey,
                    RECONCILIATION_FENCE_TOKEN,
                    repairToken
            );
        } catch (RuntimeException failure) {
            retainAutomaticFence(new RepairFence(fenceKey, repairToken));
            throw failure;
        }
        if (takeover == CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT) {
            throw inventoryMaintenance();
        }
        return new RepairFence(fenceKey, repairToken);
    }

    /** 仅在修复结果验证通过后，以令牌匹配删除 fence。 */
    private void releaseVerifiedRepairFence(RepairFence repairFence) {
        if (replicatedRedis == null) {
            Long released = redis.execute(
                    RELEASE_FENCE_SCRIPT,
                    List.of(repairFence.key()),
                    repairFence.token()
            );
            if (released == null || released == 0L) {
                throw inventoryMaintenance();
            }
            return;
        }
        CommerceRedisReplicationExecutor.ReplicatedMutation released =
                replicatedRedis.deleteIfEquals(
                        "consistency_repair_release",
                        repairFence.key(),
                        repairFence.token()
                );
        if (released == CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT) {
            throw inventoryMaintenance();
        }
    }

    /**
     * 修复失败时把人工令牌恢复为巡检 fence；无法恢复时至少保留现有令牌继续失败关闭。
     */
    private void retainAutomaticFence(RepairFence repairFence) {
        if (replicatedRedis == null) return;
        try {
            CommerceRedisReplicationExecutor.ReplicatedMutation restored =
                    replicatedRedis.compareAndSet(
                            "consistency_repair_restore_reconciliation_fence",
                            repairFence.key(),
                            repairFence.token(),
                            RECONCILIATION_FENCE_TOKEN
                    );
            if (restored == CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT
                    && redis.opsForValue().get(repairFence.key()) == null) {
                replicatedRedis.setIfAbsent(
                        "consistency_repair_restore_missing_fence",
                        repairFence.key(),
                        RECONCILIATION_FENCE_TOKEN
                );
            }
        } catch (RuntimeException restoreFailure) {
            // 残留 repair token 本身仍会关闭准入；若键已丢失且恢复失败，则需要人工介入，故记录错误。
            log.error(
                    "Unable to restore automatic Commerce inventory fence for {}",
                    repairFence.key(),
                    restoreFailure
            );
        }
    }

    /** 以稳定哈希拒绝过期快照，防止修复覆盖采样后发生的业务变化。 */
    private void requireEvidenceHash(Map<String, Object> evidence, String expectedHash) {
        if (!String.valueOf(evidence.get("evidenceHash")).equals(expectedHash)) {
            throw new CommerceApiException(
                    "COMMERCE_STALE_EVIDENCE",
                    "Consistency evidence changed",
                    409
            );
        }
    }

    private CommerceApiException inventoryMaintenance() {
        return new CommerceApiException(
                "COMMERCE_INVENTORY_MAINTENANCE",
                "Voucher inventory is already under maintenance",
                409
        );
    }

    private void renewAndAssert(CommerceRuntimeMutationLeases.MutationLease runtimeLease) {
        if (runtimeLease != null) runtimeLease.renewAndAssert();
    }

    /**
     * 重新采集快照并返回明确 verified 标志。
     *
     * @param voucherId Voucher ID
     * @return 验证结论、证据哈希和完整快照
     */
    public Map<String, Object> verify(long voucherId) {
        Map<String, Object> snapshot = snapshot(voucherId);
        return Map.of(
                "voucherId", voucherId,
                "verified", snapshot.get("consistent"),
                "evidenceHash", snapshot.get("evidenceHash"),
                "snapshot", snapshot
        );
    }

    private Integer parseStock(String raw) {
        if (raw == null) return null;
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 汇总各分片订单 Stream 的消费组 Pending 和投递位置。
     *
     * @return 全局诊断；任一分片不可读时 available/drained 均为 false
     */
    public Map<String, Object> globalStreamHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("scope", "global");
        health.put("streams", keyspace.streams());
        health.put("consumerGroup", CommerceRedisKeyspace.ORDER_CONSUMER_GROUP);
        List<Map<String, Object>> shardHealth = new ArrayList<>();
        long totalPending = 0L;
        boolean available = true;
        boolean drained = true;
        for (String streamKey : keyspace.streams()) {
            Map<String, Object> shard = new LinkedHashMap<>();
            shard.put("stream", streamKey);
            try {
                StreamInfo.XInfoGroups groups = redis.opsForStream().groups(streamKey);
                StreamInfo.XInfoGroup group = groups.stream()
                        .filter(candidate -> CommerceRedisKeyspace.ORDER_CONSUMER_GROUP.equals(candidate.groupName()))
                        .findFirst()
                        .orElseThrow();
                StreamInfo.XInfoStream stream = redis.opsForStream().info(streamKey);
                long pending = group.pendingCount() == null ? 0L : group.pendingCount();
                String delivered = group.lastDeliveredId();
                String generated = stream.lastGeneratedId();
                boolean shardDrained = pending == 0
                        && generated != null
                        && generated.equals(delivered);
                totalPending += pending;
                drained &= shardDrained;
                shard.put("available", true);
                shard.put("pending", pending);
                shard.put("drained", shardDrained);
                shard.put("lastDeliveredId", delivered);
                shard.put("lastGeneratedId", generated);
            } catch (RuntimeException unavailableShard) {
                available = false;
                drained = false;
                shard.put("available", false);
                shard.put("error", "stream diagnostics unavailable");
            }
            shardHealth.add(shard);
        }
        health.put("available", available);
        health.put("pending", totalPending);
        health.put("drained", drained);
        health.put("shards", shardHealth);
        return health;
    }

    private List<String> sortedStrings(Collection<?> values) {
        TreeSet<String> sorted = new TreeSet<>();
        values.stream()
                .filter(value -> value != null)
                .map(String::valueOf)
                .forEach(sorted::add);
        return List.copyOf(sorted);
    }

    private List<String> difference(List<String> left, List<String> right) {
        Set<String> difference = new LinkedHashSet<>(left);
        difference.removeAll(new LinkedHashSet<>(right));
        return List.copyOf(difference);
    }

    /** 对确定顺序的证据 JSON 计算 SHA-256，作为修复的乐观并发令牌。 */
    private String hash(Object evidence) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(evidence).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash commerce consistency evidence", error);
        }
    }

    /**
     * 一次一致性采样结果。
     *
     * @param evidence 对外证据及哈希
     * @param databaseBuyerIds 修复 Redis 买家集合所需的 MySQL 权威用户 ID
     */
    private record ConsistencyCapture(
            Map<String, Object> evidence,
            List<Long> databaseBuyerIds
    ) {}

    /**
     * 本次修复持有的 Redis fence 所有权。
     *
     * @param key fence 键
     * @param token 仅本次修复知道的令牌
     */
    private record RepairFence(
            String key,
            String token
    ) {}
}
