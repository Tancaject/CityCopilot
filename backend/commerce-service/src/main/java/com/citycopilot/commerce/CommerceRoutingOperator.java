package com.citycopilot.commerce;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Commerce Redis 路由控制面的生产级一次性运维任务。
 *
 * <p>运维进程复用普通 Commerce 制品，但关闭 API、Worker 等运行时角色。所有 Redis 控制面写委托给
 * {@link CommerceRedisReplicationExecutor}，使 Lua CAS/SET 与 WAIT 使用同一主节点物理连接，兼容
 * Spring/Lettuce 配置的 Cluster、TLS 与 ACL。迁移顺序是关闭准入、证明排空、校验目标数据、
 * CAS 切换全局所有权，最后逐分片开放目标门禁。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.commerce.routing-operator",
        name = "enabled",
        havingValue = "true"
)
final class CommerceRoutingOperator implements ApplicationRunner {
    /** 路由激活前必须存在的核心 Commerce 表数量。 */
    private static final int REQUIRED_COMMERCE_TABLES = 11;

    /** 查询业务排空证据并迁移 PREPARED 准入归属。 */
    private final JdbcTemplate jdbc;
    /** 读取控制记录、门禁、Stream 和目标业务数据。 */
    private final StringRedisTemplate redis;
    /** 对控制面 CAS/SET 执行副本确认。 */
    private final CommerceRedisReplicationExecutor replicatedRedis;
    /** 获取运维互斥租约并统计在途业务租约。 */
    private final CommerceRuntimeMutationLeases runtimeLeases;
    /** 当前部署的源路由键空间。 */
    private final CommerceRedisKeyspace source;
    /** 将结果或失败证据打印为结构化 JSON。 */
    private final ObjectMapper objectMapper;
    /** 本次一次性进程要执行的动作。 */
    private final Action action;
    /** ACTIVATE 动作的目标代际。 */
    private final String targetGeneration;
    /** ACTIVATE 动作的目标物理分片数。 */
    private final int targetPhysicalShardCount;
    /** 标记 ApplicationRunner 已成功完成，供 main 主动退出进程。 */
    private volatile boolean completed;
    /** 当前运维租约；每次关键写前必须续租确认。 */
    private volatile CommerceRuntimeMutationLeases.OperatorLease activeOperatorLease;

    CommerceRoutingOperator(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            CommerceRedisReplicationExecutor replicatedRedis,
            CommerceRuntimeMutationLeases runtimeLeases,
            CommerceRedisKeyspace source,
            ObjectMapper objectMapper,
            @Value("${citycopilot.commerce.routing-operator.action:status}") String action,
            @Value("${citycopilot.commerce.routing-operator.target-generation:}")
            String targetGeneration,
            @Value("${citycopilot.commerce.routing-operator.target-physical-shard-count:0}")
            int targetPhysicalShardCount
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.replicatedRedis = replicatedRedis;
        this.runtimeLeases = runtimeLeases;
        this.source = source;
        this.objectMapper = objectMapper;
        this.action = Action.parse(action);
        this.targetGeneration = targetGeneration == null ? "" : targetGeneration.trim();
        this.targetPhysicalShardCount = targetPhysicalShardCount;
    }

    /**
     * 执行指定动作并把成功结果写 stdout；前置检查失败时把证据写 stderr 后保留非零退出。
     */
    @Override
    public void run(ApplicationArguments ignored) throws Exception {
        try {
            Map<String, Object> result = execute();
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            completed = true;
        } catch (RoutingPreflightException failed) {
            System.err.println(
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(failed.evidence())
            );
            throw failed;
        }
    }

    boolean completed() {
        return completed;
    }

    /**
     * 分派运维动作。只读 STATUS/VERIFY_DRAIN 不持有运维租约，控制面写动作必须独占租约。
     */
    Map<String, Object> execute() {
        if (action == Action.STATUS) return status();
        if (action == Action.VERIFY_DRAIN) return verifyDrain();
        try (CommerceRuntimeMutationLeases.OperatorLease lease = runtimeLeases.acquireOperator()) {
            activeOperatorLease = lease;
            lease.renewAndAssert();
            try {
                return switch (action) {
                    case INITIALIZE -> initialize();
                    case BEGIN_DRAIN -> beginDrain();
                    case ACTIVATE -> activate();
                    default -> throw new IllegalStateException("Unexpected routing operator action");
                };
            } finally {
                activeOperatorLease = null;
            }
        }
    }

    private Map<String, Object> status() {
        CommerceRoutingGuard.RoutingSnapshot current = readControl();
        Map<String, Object> result = baseResult("status");
        result.put("control", describe(current));
        result.put("sourceGates", gateSnapshot(source));
        return result;
    }

    /** 首次建立 ACTIVE 全局控制记录和全部源分片门禁，并确认副本复制。 */
    private Map<String, Object> initialize() {
        CommerceRoutingGuard.RoutingSnapshot current = readControl();
        if (current == null) {
            initializeGates(source, CommerceRoutingGuard.RoutingState.ACTIVE);
            String value = source.routingControlValue("ACTIVE", revision());
            assertOperatorLease();
            requireMutation(
                    "routing_control_initialize",
                    CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                    replicatedRedis.setIfAbsent(
                            "routing_control_initialize",
                            CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                            value
                    )
            );
            current = readControl();
        }
        assertFingerprint(current, source);
        if (current.state() != CommerceRoutingGuard.RoutingState.ACTIVE) {
            throw new IllegalStateException("Existing Commerce routing control is not ACTIVE");
        }
        confirmControlReplication(current, "routing_control_confirm_initialize");
        initializeGates(source, CommerceRoutingGuard.RoutingState.ACTIVE);
        assertGates(source, CommerceRoutingGuard.RoutingState.ACTIVE);

        Map<String, Object> result = baseResult("initialize");
        result.put("control", describe(current));
        result.put("safe", true);
        return result;
    }

    /**
     * 先把全局控制记录 CAS 为 DRAINING，再关闭每个源分片门禁。
     *
     * <p>全局与分片的双层关闭使新 API 请求和已经越过 API 检查的 Lua 都无法继续准入。</p>
     */
    private Map<String, Object> beginDrain() {
        CommerceRoutingGuard.RoutingSnapshot current = requireControl();
        assertFingerprint(current, source);
        if (current.state() == CommerceRoutingGuard.RoutingState.ACTIVE) {
            String expected = controlValue(current);
            String next = source.routingControlValue("DRAINING", revision());
            assertOperatorLease();
            requireMutation(
                    "routing_control_begin_drain",
                    CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                    replicatedRedis.compareAndSet(
                            "routing_control_begin_drain",
                            CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                            expected,
                            next
                    )
            );
            current = requireControl();
        }
        assertFingerprint(current, source);
        if (current.state() != CommerceRoutingGuard.RoutingState.DRAINING) {
            throw new IllegalStateException("Commerce routing control cannot enter DRAINING");
        }
        confirmControlReplication(current, "routing_control_confirm_drain");
        transitionGates(
                source,
                CommerceRoutingGuard.RoutingState.ACTIVE,
                CommerceRoutingGuard.RoutingState.DRAINING,
                "routing_gate_begin_drain"
        );
        assertGates(source, CommerceRoutingGuard.RoutingState.DRAINING);

        Map<String, Object> result = baseResult("begin-drain");
        result.put("control", describe(current));
        result.put("admissionClosed", true);
        return result;
    }

    /** 收集业务表、Outbox、恢复、租约、Stream 和在途集合证据，确认源运行时已完全排空。 */
    private Map<String, Object> verifyDrain() {
        CommerceRoutingGuard.RoutingSnapshot current = requireSourceDraining();
        Map<String, Object> drain = drainSnapshot(source);
        Map<String, Object> result = baseResult("verify-drain");
        result.put("control", describe(current));
        result.put("drain", drain);
        if (!Boolean.TRUE.equals(drain.get("safe"))) {
            throw new RoutingPreflightException("Commerce runtime is not drained", result);
        }
        return result;
    }

    /**
     * 将路由所有权切换到目标代际，并支持在全局 CAS 已提交后的崩溃恢复。
     *
     * <p>目标门禁始终先保持 DRAINING；只有源再次排空、PREPARED 归属迁移完成且目标数据通过
     * 最终校验后才逐分片开放 ACTIVE。</p>
     */
    private Map<String, Object> activate() {
        CommerceRedisKeyspace target = targetKeyspace();
        CommerceRoutingGuard.RoutingSnapshot current = requireControl();
        boolean fingerprintChanged = !sameFingerprint(source, target);
        if (current.isActiveFor(target)
                && allGatesInState(target, CommerceRoutingGuard.RoutingState.ACTIVE)) {
            confirmControlReplication(current, "routing_control_confirm_already_active");
            Map<String, Object> result = baseResult("activate");
            result.put("control", describe(current));
            result.put("target", fingerprint(target));
            result.put("activated", true);
            result.put("alreadyActivated", true);
            return result;
        }
        boolean targetCommitted = current.isActiveFor(target)
                && (fingerprintChanged || anyGateInState(target, CommerceRoutingGuard.RoutingState.DRAINING));

        Map<String, Object> drain = null;
        Map<String, Object> preCasPreflight = null;
        Map<String, Object> preflight = null;
        Map<String, Object> preparedMigration;
        if (!targetCommitted) {
            assertFingerprint(current, source);
            if (current.state() != CommerceRoutingGuard.RoutingState.DRAINING) {
                throw new IllegalStateException("BeginDrain must complete before Activate");
            }
            assertGates(source, CommerceRoutingGuard.RoutingState.DRAINING);
            drain = drainSnapshot(source);
            if (!Boolean.TRUE.equals(drain.get("safe"))) {
                Map<String, Object> evidence = baseResult("activate");
                evidence.put("control", describe(current));
                evidence.put("drain", drain);
                throw new RoutingPreflightException("Commerce runtime is not drained", evidence);
            }
            preCasPreflight = targetPreflight(target);
            if (!Boolean.TRUE.equals(preCasPreflight.get("safe"))) {
                Map<String, Object> evidence = baseResult("activate");
                evidence.put("control", describe(current));
                evidence.put("targetPreflight", preCasPreflight);
                throw new RoutingPreflightException(
                        "Target Commerce routing keyspace failed business-data preflight",
                        evidence
                );
            }
            initializeGates(target, CommerceRoutingGuard.RoutingState.DRAINING);
            assertGates(target, CommerceRoutingGuard.RoutingState.DRAINING);
            String next = target.routingControlValue("ACTIVE", revision());
            assertOperatorLease();
            requireMutation(
                    "routing_control_activate",
                    CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                    replicatedRedis.compareAndSet(
                            "routing_control_activate",
                            CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                            controlValue(current),
                            next
                    )
            );
            current = requireControl();
            assertFingerprint(current, target);
            // 只有赢得全局 CAS 的运维进程才有权迁移 PREPARED 准入的路由归属。
            preparedMigration = migratePreparedAdmissions(source, target);
            preflight = targetPreflight(target);
            if (!Boolean.TRUE.equals(preflight.get("safe"))) {
                Map<String, Object> evidence = baseResult("activate-post-cas");
                evidence.put("control", describe(current));
                evidence.put("targetPreflight", preflight);
                throw new RoutingPreflightException(
                        "Target Commerce routing keyspace changed after control CAS",
                        evidence
                );
            }
        } else {
            if (fingerprintChanged) {
                assertGates(source, CommerceRoutingGuard.RoutingState.DRAINING);
            }
            // 开放目标分片期间崩溃可能留下混合门禁；先重新关闭全部目标分片，等待之前抢先取得的
            // 准入租约结束，再恢复 MySQL 归属迁移。
            transitionGates(
                    target,
                    CommerceRoutingGuard.RoutingState.ACTIVE,
                    CommerceRoutingGuard.RoutingState.DRAINING,
                    "routing_gate_close_activate_resume"
            );
            assertGates(target, CommerceRoutingGuard.RoutingState.DRAINING);
            // 前次写可能已到主节点但 WAIT 失败；修改 MySQL 前先同值重写并重新取得复制确认。
            confirmControlReplication(current, "routing_control_confirm_activate_resume");
            preparedMigration = migratePreparedAdmissions(source, target);
            // 崩溃可能留下“全局 CAS 已提交、目标门禁全关闭”；每次恢复都重验业务数据，
            // 因为崩溃前采集的证据已经可能过期。
            preflight = targetPreflight(target);
            if (!Boolean.TRUE.equals(preflight.get("safe"))) {
                Map<String, Object> evidence = baseResult("activate-resume");
                evidence.put("control", describe(current));
                evidence.put("targetPreflight", preflight);
                throw new RoutingPreflightException(
                        "Target Commerce routing keyspace failed resume preflight",
                        evidence
                );
            }
        }

        Map<String, Object> postCasDrain = drainSnapshot(source);
        if (!Boolean.TRUE.equals(postCasDrain.get("safe"))) {
            Map<String, Object> evidence = baseResult("activate-post-cas-drain");
            evidence.put("control", describe(current));
            evidence.put("drain", postCasDrain);
            throw new RoutingPreflightException(
                    "Source Commerce runtime changed after control CAS",
                    evidence
            );
        }
        // 最后一轮 PREPARED 迁移和目标校验都在运维租约阻挡业务写、目标门禁尚未开放时完成。
        Map<String, Object> preparedPostflight = migratePreparedAdmissions(source, target);
        preflight = targetPreflight(target);
        if (!Boolean.TRUE.equals(preflight.get("safe"))) {
            Map<String, Object> evidence = baseResult("activate-final-preflight");
            evidence.put("control", describe(current));
            evidence.put("targetPreflight", preflight);
            throw new RoutingPreflightException(
                    "Target Commerce routing keyspace failed final preflight",
                    evidence
            );
        }
        confirmControlReplication(current, "routing_control_confirm_activate");
        transitionGates(
                target,
                CommerceRoutingGuard.RoutingState.DRAINING,
                CommerceRoutingGuard.RoutingState.ACTIVE,
                "routing_gate_activate"
        );
        assertGates(target, CommerceRoutingGuard.RoutingState.ACTIVE);

        Map<String, Object> result = baseResult("activate");
        result.put("control", describe(current));
        result.put("source", fingerprint(source));
        result.put("target", fingerprint(target));
        result.put("activated", true);
        result.put("resumedAfterCommittedCas", targetCommitted);
        if (drain != null) result.put("drain", drain);
        result.put("postCasDrain", postCasDrain);
        if (preCasPreflight != null) result.put("targetPreflightBeforeCas", preCasPreflight);
        result.put("targetPreflight", preflight);
        result.put("preparedMigration", preparedMigration);
        result.put("preparedPostflight", preparedPostflight);
        return result;
    }

    /**
     * 仅迁移尚未被 Redis 接受的 PREPARED 行；ACCEPTED/终态行始终保留原路由归属。
     */
    private Map<String, Object> migratePreparedAdmissions(
            CommerceRedisKeyspace sourceKeyspace,
            CommerceRedisKeyspace targetKeyspace
    ) {
        assertOperatorLease();
        boolean changedFingerprint = !sameFingerprint(sourceKeyspace, targetKeyspace);
        int migrated = 0;
        if (changedFingerprint) {
            migrated = jdbc.update("""
                    UPDATE commerce_admission_ledger
                    SET routing_generation=?,physical_shard_count=?,
                        updated_at=CURRENT_TIMESTAMP(3)
                    WHERE routing_generation=? AND physical_shard_count=?
                      AND status='PREPARED'
                    """,
                    targetKeyspace.routingGeneration(),
                    targetKeyspace.physicalShardCount(),
                    sourceKeyspace.routingGeneration(),
                    sourceKeyspace.physicalShardCount()
            );
        }
        long sourceRemaining = changedFingerprint ? count("""
                SELECT COUNT(*) FROM commerce_admission_ledger
                WHERE routing_generation=? AND physical_shard_count=? AND status='PREPARED'
                """,
                sourceKeyspace.routingGeneration(),
                sourceKeyspace.physicalShardCount()
        ) : 0L;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("migrated", migrated);
        result.put("sourcePreparedRemaining", sourceRemaining);
        result.put("target", fingerprint(targetKeyspace));
        result.put("safe", sourceRemaining == 0L);
        if (sourceRemaining != 0L) {
            throw new RoutingPreflightException(
                    "Source PREPARED admissions appeared during routing activation",
                    result
            );
        }
        return result;
    }

    private CommerceRoutingGuard.RoutingSnapshot requireSourceDraining() {
        CommerceRoutingGuard.RoutingSnapshot current = requireControl();
        assertFingerprint(current, source);
        if (current.state() != CommerceRoutingGuard.RoutingState.DRAINING) {
            throw new IllegalStateException("BeginDrain must complete before VerifyDrain");
        }
        assertGates(source, CommerceRoutingGuard.RoutingState.DRAINING);
        return current;
    }

    /** 以 SET NX 初始化全部分片门禁，不覆盖已有冲突状态。 */
    private void initializeGates(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        String value = gateValue(keyspace, state);
        for (String gate : keyspace.admissionRoutingGates()) {
            assertOperatorLease();
            CommerceRedisReplicationExecutor.ReplicatedMutation mutation =
                    replicatedRedis.setIfAbsent("routing_gate_initialize", gate, value);
            requireMutation("routing_gate_initialize", gate, mutation);
        }
    }

    /** 对每个分片门禁执行带副本确认的 CAS 状态转换。 */
    private void transitionGates(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState expectedState,
            CommerceRoutingGuard.RoutingState nextState,
            String operation
    ) {
        String expected = gateValue(keyspace, expectedState);
        String next = gateValue(keyspace, nextState);
        for (String gate : keyspace.admissionRoutingGates()) {
            assertOperatorLease();
            requireMutation(
                    operation,
                    gate,
                    replicatedRedis.compareAndSet(operation, gate, expected, next)
            );
        }
    }

    private void requireMutation(
            String operation,
            String key,
            CommerceRedisReplicationExecutor.ReplicatedMutation mutation
    ) {
        if (mutation == CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT) {
            throw new IllegalStateException(
                    "Concurrent or conflicting Redis routing mutation: operation="
                            + operation + ", key=" + key
            );
        }
    }

    /** 对已提交控制值执行同值 CAS + WAIT，供恢复进程重新取得复制证明。 */
    private void confirmControlReplication(
            CommerceRoutingGuard.RoutingSnapshot current,
            String operation
    ) {
        assertOperatorLease();
        String value = controlValue(current);
        requireMutation(
                operation,
                CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                replicatedRedis.compareAndSet(
                        operation,
                        CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                        value,
                        value
                )
        );
    }

    /** 在每个关键控制面写前续租；运维所有权丢失后立即中止。 */
    private void assertOperatorLease() {
        CommerceRuntimeMutationLeases.OperatorLease lease = activeOperatorLease;
        if (lease == null) {
            throw new IllegalStateException("Commerce routing operator lease is not held");
        }
        lease.renewAndAssert();
    }

    private void assertGates(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        String expected = gateValue(keyspace, state);
        for (String gate : keyspace.admissionRoutingGates()) {
            String current = redis.opsForValue().get(gate);
            if (!expected.equals(current)) {
                throw new IllegalStateException(
                        "Commerce routing gate mismatch: key=" + gate
                                + ", expected=" + expected + ", actual=" + current
                );
            }
        }
    }

    private boolean anyGateInState(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        String expected = gateValue(keyspace, state);
        for (String gate : keyspace.admissionRoutingGates()) {
            if (expected.equals(redis.opsForValue().get(gate))) return true;
        }
        return false;
    }

    private boolean allGatesInState(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        String expected = gateValue(keyspace, state);
        for (String gate : keyspace.admissionRoutingGates()) {
            if (!expected.equals(redis.opsForValue().get(gate))) return false;
        }
        return true;
    }

    private Map<String, Object> gateSnapshot(CommerceRedisKeyspace keyspace) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.putAll(fingerprint(keyspace));
        List<Map<String, Object>> gates = new ArrayList<>();
        for (int shard = 0; shard < keyspace.admissionRoutingGates().size(); shard++) {
            String key = keyspace.admissionRoutingGates().get(shard);
            gates.add(Map.of(
                    "shard", shard,
                    "key", key,
                    "value", Objects.toString(redis.opsForValue().get(key), "")
            ));
        }
        snapshot.put("gates", gates);
        return snapshot;
    }

    /**
     * 聚合迁移所需的完整排空证明；任一计数、租约或 Redis 分片不安全时 safe=false。
     */
    private Map<String, Object> drainSnapshot(CommerceRedisKeyspace keyspace) {
        Map<String, Object> integrity = integritySnapshot();
        Map<String, Long> ledger = ledgerSnapshot(keyspace);
        List<Long> voucherIds = voucherIds();
        Map<String, Object> redisState = redisDrainState(keyspace, voucherIds);
        long activeRuntimeMutations = runtimeLeases.activeMutationCount();
        long activeAdmissionMutations = runtimeLeases.activeAdmissionCount(keyspace);
        long metadataUpdateFences = metadataUpdateFenceCount(keyspace, voucherIds);

        long unpublishedOutbox = count("""
                SELECT COUNT(*) FROM commerce_outbox_event
                WHERE status <> 'PUBLISHED'
                """);
        long inFlightReservations = count("""
                SELECT COUNT(*) FROM commerce_reservation
                WHERE status IN ('RESERVED','RELEASE_REQUIRED')
                """);
        long unreplayedDeadLetters = count("""
                SELECT COUNT(*) FROM commerce_dead_letter WHERE replayed_at IS NULL
                """);
        long unresolvedRecovery = count("""
                SELECT COUNT(*) FROM commerce_order_recovery
                WHERE phase IS NULL
                   OR phase NOT IN ('ORDER_CREATED','DEAD_LETTERED','RESTORED')
                """);

        boolean safe = Boolean.TRUE.equals(integrity.get("safe"))
                && unpublishedOutbox == 0
                && inFlightReservations == 0
                && unreplayedDeadLetters == 0
                && unresolvedRecovery == 0
                && ledger.get("accepted") == 0
                && ledger.get("repairRequired") == 0
                && ledger.get("activeClaims") == 0
                && activeRuntimeMutations == 0
                && activeAdmissionMutations == 0
                && metadataUpdateFences == 0
                && Boolean.TRUE.equals(redisState.get("drained"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(fingerprint(keyspace));
        result.put("integrity", integrity);
        result.put("unpublishedOutbox", unpublishedOutbox);
        result.put("inFlightReservations", inFlightReservations);
        result.put("unreplayedDeadLetters", unreplayedDeadLetters);
        result.put("unresolvedRecovery", unresolvedRecovery);
        result.put("admissionLedger", ledger);
        result.put("activeRuntimeMutations", activeRuntimeMutations);
        result.put("activeAdmissionMutations", activeAdmissionMutations);
        result.put("metadataUpdateFences", metadataUpdateFences);
        result.put("redis", redisState);
        result.put("safe", safe);
        return result;
    }

    /** 检查关键表、非负库存和 ID 号段种子，避免把损坏的数据库状态带入新路由。 */
    private Map<String, Object> integritySnapshot() {
        Long tableCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT table_name)
                FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN (
                    'commerce_voucher','commerce_seckill_voucher','shop_summary_projection',
                    'commerce_order','commerce_reservation','commerce_outbox_event',
                    'commerce_inbox_event','commerce_dead_letter','commerce_order_recovery',
                    'commerce_admission_ledger','commerce_id_segment'
                  )
                """, Long.class);
        long negativeStock = count(
                "SELECT COUNT(*) FROM commerce_seckill_voucher WHERE stock < 0"
        );
        long actualTables = tableCount == null ? 0L : tableCount;
        long orderSegment = count("""
                SELECT COUNT(*) FROM commerce_id_segment
                WHERE namespace='order' AND next_value >= 0
                """);
        long voucherSegment = count("""
                SELECT COUNT(*) FROM commerce_id_segment
                WHERE namespace='voucher' AND next_value >= 0
                """);
        return Map.of(
                "requiredTables", REQUIRED_COMMERCE_TABLES,
                "actualTables", actualTables,
                "negativeStockRows", negativeStock,
                "orderIdSegmentSeeded", orderSegment == 1,
                "voucherIdSegmentSeeded", voucherSegment == 1,
                "safe", actualTables == REQUIRED_COMMERCE_TABLES
                        && negativeStock == 0
                        && orderSegment == 1
                        && voucherSegment == 1
        );
    }

    private Map<String, Long> ledgerSnapshot(CommerceRedisKeyspace keyspace) {
        Object[] fingerprint = {
                keyspace.routingGeneration(),
                keyspace.physicalShardCount()
        };
        long accepted = count("""
                SELECT COUNT(*) FROM commerce_admission_ledger
                WHERE routing_generation=? AND physical_shard_count=? AND status='ACCEPTED'
                """, fingerprint);
        long repairRequired = count("""
                SELECT COUNT(*) FROM commerce_admission_ledger
                WHERE routing_generation=? AND physical_shard_count=? AND status='REPAIR_REQUIRED'
                """, fingerprint);
        long activeClaims = count("""
                SELECT COUNT(*) FROM commerce_admission_ledger
                WHERE routing_generation=? AND physical_shard_count=?
                  AND claim_token IS NOT NULL AND claimed_until>=CURRENT_TIMESTAMP(3)
                """, fingerprint);
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("accepted", accepted);
        result.put("repairRequired", repairRequired);
        result.put("activeClaims", activeClaims);
        return result;
    }

    /**
     * 汇总每个分片的 Pending、未投递尾部和 Voucher 在途集合；三者均为零才算排空。
     */
    private Map<String, Object> redisDrainState(
            CommerceRedisKeyspace keyspace,
            List<Long> voucherIds
    ) {
        long[] inFlightByShard = new long[keyspace.physicalShardCount()];
        for (Long voucherId : voucherIds) {
            Long size = redis.opsForSet().size(keyspace.inflight(voucherId));
            if (size == null) {
                throw new IllegalStateException(
                        "Unable to read Commerce in-flight set for voucher " + voucherId
                );
            }
            inFlightByShard[keyspace.physicalShard(voucherId)] += size;
        }

        long totalPending = 0L;
        long totalLag = 0L;
        long totalInFlight = 0L;
        boolean available = true;
        boolean drained = true;
        List<Map<String, Object>> shards = new ArrayList<>();
        for (int shard = 0; shard < keyspace.streams().size(); shard++) {
            String stream = keyspace.streams().get(shard);
            Map<String, Object> streamState = streamState(stream);
            long pending = number(streamState.get("pending"));
            long lag = number(streamState.get("lag"));
            long inFlight = inFlightByShard[shard];
            boolean shardAvailable = Boolean.TRUE.equals(streamState.get("available"));
            boolean shardDrained = shardAvailable && pending == 0 && lag == 0 && inFlight == 0;
            totalPending += pending;
            totalLag += lag;
            totalInFlight += inFlight;
            available &= shardAvailable;
            drained &= shardDrained;
            Map<String, Object> state = new LinkedHashMap<>(streamState);
            state.put("shard", shard);
            state.put("stream", stream);
            state.put("inFlight", inFlight);
            state.put("drained", shardDrained);
            shards.add(state);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("pending", totalPending);
        result.put("lag", totalLag);
        result.put("inFlight", totalInFlight);
        result.put("drained", available && drained);
        result.put("shards", shards);
        return result;
    }

    /**
     * 读取单个 Stream 的消费组状态；非空 Stream 缺失消费组属于不安全状态。
     */
    private Map<String, Object> streamState(String stream) {
        Boolean exists = redis.hasKey(stream);
        if (!Boolean.TRUE.equals(exists)) {
            return new LinkedHashMap<>(Map.of(
                    "available", true,
                    "exists", false,
                    "pending", 0L,
                    "lag", 0L
            ));
        }
        try {
            Long lengthValue = redis.opsForStream().size(stream);
            long length = lengthValue == null ? -1L : lengthValue;
            if (length < 0) throw new IllegalStateException("Redis Stream length is unavailable");

            StreamInfo.XInfoGroups groups = redis.opsForStream().groups(stream);
            StreamInfo.XInfoGroup group = groups.stream()
                    .filter(candidate -> CommerceRedisKeyspace.ORDER_CONSUMER_GROUP.equals(candidate.groupName()))
                    .findFirst()
                    .orElse(null);
            if (group == null) {
                if (length == 0) {
                    return new LinkedHashMap<>(Map.of(
                            "available", true,
                            "exists", true,
                            "length", 0L,
                            "pending", 0L,
                            "lag", 0L,
                            "groupPresent", false
                    ));
                }
                throw new IllegalStateException("Commerce consumer group is missing");
            }
            long pending = group.pendingCount() == null ? 0L : group.pendingCount();
            String delivered = group.lastDeliveredId();
            String generated = redis.opsForStream().info(stream).lastGeneratedId();
            long lag = length == 0 || generated != null && generated.equals(delivered) ? 0L : -1L;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", true);
            result.put("exists", true);
            result.put("length", length);
            result.put("pending", pending);
            result.put("lag", lag);
            result.put("groupPresent", true);
            result.put("lastDeliveredId", Objects.toString(delivered, ""));
            result.put("lastGeneratedId", Objects.toString(generated, ""));
            return result;
        } catch (RuntimeException failure) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", false);
            result.put("exists", true);
            result.put("pending", 0L);
            result.put("lag", -1L);
            result.put("error", failure.getClass().getSimpleName());
            return result;
        }
    }

    /**
     * 将目标键空间的库存、活动元数据、买家集合逐项与 MySQL 权威事实比对。
     *
     * <p>同时要求目标准入账本终态、Stream 排空、无元数据 fence、无准入租约。</p>
     */
    private Map<String, Object> targetPreflight(CommerceRedisKeyspace target) {
        List<VoucherProjection> vouchers = voucherProjections();
        Map<Long, Set<String>> expectedBuyers = expectedBuyers();
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> voucherResults = new ArrayList<>();

        for (VoucherProjection voucher : vouchers) {
            String redisStock = redis.opsForValue().get(target.stock(voucher.id()));
            boolean stockMatches = String.valueOf(voucher.stock()).equals(redisStock);
            if (!stockMatches) {
                errors.add("voucher=" + voucher.id() + " target stock mismatch");
            }

            Map<Object, Object> metadata = redis.opsForHash().entries(target.campaignMeta(voucher.id()));
            boolean metadataMatches = String.valueOf(voucher.id()).equals(text(metadata.get("voucherId")))
                    && String.valueOf(voucher.type()).equals(text(metadata.get("type")))
                    && String.valueOf(voucher.status()).equals(text(metadata.get("status")))
                    && String.valueOf(voucher.beginEpochMillis())
                    .equals(text(metadata.get("beginEpochMillis")))
                    && String.valueOf(voucher.endEpochMillis())
                    .equals(text(metadata.get("endEpochMillis")))
                    && voucher.sourceVersion().equals(text(metadata.get("sourceVersion")));
            if (!metadataMatches) {
                errors.add("voucher=" + voucher.id() + " target metadata mismatch");
            }

            Set<String> actualBuyers = redis.opsForSet().members(target.buyers(voucher.id()));
            Set<String> normalizedActual = actualBuyers == null
                    ? Set.of()
                    : new TreeSet<>(actualBuyers);
            Set<String> normalizedExpected = expectedBuyers.getOrDefault(voucher.id(), Set.of());
            boolean buyersMatch = normalizedExpected.equals(normalizedActual);
            if (!buyersMatch) {
                errors.add("voucher=" + voucher.id() + " target buyers mismatch");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("voucherId", voucher.id());
            result.put("shard", target.physicalShard(voucher.id()));
            result.put("stockMatches", stockMatches);
            result.put("metadataMatches", metadataMatches);
            result.put("buyersMatch", buyersMatch);
            result.put("databaseBuyerCount", normalizedExpected.size());
            result.put("redisBuyerCount", normalizedActual.size());
            voucherResults.add(result);
        }

        Map<String, Long> ledger = ledgerSnapshot(target);
        if (ledger.values().stream().anyMatch(value -> value != 0L)) {
            errors.add("target admission ledger is not terminal");
        }
        Map<String, Object> redisState = redisDrainState(
                target,
                vouchers.stream().map(VoucherProjection::id).toList()
        );
        if (!Boolean.TRUE.equals(redisState.get("drained"))) {
            errors.add("target keyspace contains in-flight Stream work");
        }
        long metadataUpdateFences = metadataUpdateFenceCount(
                target,
                vouchers.stream().map(VoucherProjection::id).toList()
        );
        if (metadataUpdateFences != 0L) {
            errors.add("target keyspace contains campaign metadata update fences");
        }
        long activeAdmissionMutations = runtimeLeases.activeAdmissionCount(target);
        if (activeAdmissionMutations != 0L) {
            errors.add("target keyspace contains active admission mutations");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(fingerprint(target));
        result.put("safe", errors.isEmpty());
        result.put("errors", errors);
        result.put("admissionLedger", ledger);
        result.put("vouchers", voucherResults);
        result.put("redis", redisState);
        result.put("metadataUpdateFences", metadataUpdateFences);
        result.put("activeAdmissionMutations", activeAdmissionMutations);
        return result;
    }

    private long metadataUpdateFenceCount(
            CommerceRedisKeyspace keyspace,
            Collection<Long> voucherIds
    ) {
        long fences = 0L;
        for (Long voucherId : voucherIds) {
            if (Boolean.TRUE.equals(redis.hasKey(keyspace.campaignUpdateFence(voucherId)))) {
                fences++;
            }
        }
        return fences;
    }

    private List<VoucherProjection> voucherProjections() {
        return jdbc.query("""
                SELECT s.voucher_id,s.stock,v.type,v.status,s.begin_time,s.end_time,
                       v.version AS voucher_version,s.version AS seckill_version
                FROM commerce_seckill_voucher s
                JOIN commerce_voucher v ON v.id=s.voucher_id
                ORDER BY s.voucher_id
                """, (result, row) -> new VoucherProjection(
                result.getLong("voucher_id"),
                result.getInt("stock"),
                result.getInt("type"),
                result.getInt("status"),
                epochMillis(result.getTimestamp("begin_time")),
                epochMillis(result.getTimestamp("end_time")),
                result.getLong("voucher_version") + ":" + result.getLong("seckill_version")
        ));
    }

    private Map<Long, Set<String>> expectedBuyers() {
        Map<Long, Set<String>> buyers = new LinkedHashMap<>();
        jdbc.query("""
                SELECT voucher_id,user_id FROM (
                  SELECT voucher_id,user_id FROM commerce_order
                  UNION
                  SELECT voucher_id,user_id FROM commerce_reservation WHERE status='RESERVED'
                ) expected_buyers
                ORDER BY voucher_id,user_id
                """, result -> {
            buyers.computeIfAbsent(
                    result.getLong("voucher_id"),
                    ignored -> new TreeSet<>()
            ).add(String.valueOf(result.getLong("user_id")));
        });
        return buyers;
    }

    private List<Long> voucherIds() {
        return jdbc.queryForList(
                "SELECT voucher_id FROM commerce_seckill_voucher ORDER BY voucher_id",
                Long.class
        );
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        if (value == null) throw new IllegalStateException("Commerce drain query returned null");
        return value;
    }

    private CommerceRedisKeyspace targetKeyspace() {
        if (targetGeneration.isBlank()
                || targetPhysicalShardCount < 1
                || targetPhysicalShardCount > 1024) {
            throw new IllegalArgumentException(
                    "Activate requires a valid target routing generation and shard count"
            );
        }
        return new CommerceRedisKeyspace(targetPhysicalShardCount, targetGeneration);
    }

    private CommerceRoutingGuard.RoutingSnapshot requireControl() {
        CommerceRoutingGuard.RoutingSnapshot current = readControl();
        if (current == null) {
            throw new IllegalStateException("Commerce routing control record is missing");
        }
        return current;
    }

    private CommerceRoutingGuard.RoutingSnapshot readControl() {
        return CommerceRoutingGuard.parse(
                redis.opsForValue().get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY)
        );
    }

    private void assertFingerprint(
            CommerceRoutingGuard.RoutingSnapshot snapshot,
            CommerceRedisKeyspace expected
    ) {
        if (snapshot == null || !snapshot.matches(expected)) {
            throw new IllegalStateException(
                    "Commerce routing fingerprint mismatch: expected="
                            + expected.routingGeneration() + "/" + expected.physicalShardCount()
            );
        }
    }

    private String controlValue(CommerceRoutingGuard.RoutingSnapshot snapshot) {
        return new CommerceRedisKeyspace(
                snapshot.physicalShardCount(),
                snapshot.generation()
        ).routingControlValue(snapshot.state().name(), snapshot.revision());
    }

    private String gateValue(
            CommerceRedisKeyspace keyspace,
            CommerceRoutingGuard.RoutingState state
    ) {
        return state == CommerceRoutingGuard.RoutingState.ACTIVE
                ? keyspace.activeAdmissionGateValue()
                : keyspace.drainingAdmissionGateValue();
    }

    private Map<String, Object> baseResult(String operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("observedAt", Instant.now().toString());
        result.put("source", fingerprint(source));
        return result;
    }

    private Map<String, Object> describe(CommerceRoutingGuard.RoutingSnapshot snapshot) {
        if (snapshot == null) return Map.of("present", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("present", true);
        result.put("state", snapshot.state().name());
        result.put("generation", snapshot.generation());
        result.put("physicalShardCount", snapshot.physicalShardCount());
        result.put("revision", snapshot.revision());
        return result;
    }

    private Map<String, Object> fingerprint(CommerceRedisKeyspace keyspace) {
        return Map.of(
                "generation", keyspace.routingGeneration(),
                "physicalShardCount", keyspace.physicalShardCount()
        );
    }

    private boolean sameFingerprint(
            CommerceRedisKeyspace left,
            CommerceRedisKeyspace right
    ) {
        return left.routingGeneration().equals(right.routingGeneration())
                && left.physicalShardCount() == right.physicalShardCount();
    }

    private long epochMillis(Timestamp value) {
        if (value == null) throw new IllegalStateException("Voucher campaign time is null");
        return value.toInstant().toEpochMilli();
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException("Expected numeric routing evidence");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String revision() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 目标路由预检使用的 MySQL Voucher 权威投影。
     */
    private record VoucherProjection(
            long id,
            int stock,
            int type,
            int status,
            long beginEpochMillis,
            long endEpochMillis,
            String sourceVersion
    ) {}

    /** 一次性路由运维进程支持的动作。 */
    enum Action {
        /** 只读查看控制记录和门禁。 */
        STATUS,
        /** 首次初始化控制面。 */
        INITIALIZE,
        /** 关闭新业务写和准入。 */
        BEGIN_DRAIN,
        /** 只读验证源运行时是否排空。 */
        VERIFY_DRAIN,
        /** 校验并切换到目标路由代际。 */
        ACTIVATE;

        /** 将配置中的连字符/大小写形式解析为标准动作。 */
        static Action parse(String raw) {
            String normalized = Objects.requireNonNull(raw, "action")
                    .trim()
                    .replace('-', '_')
                    .toUpperCase(Locale.ROOT);
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Unsupported Commerce routing operator action: " + raw,
                        invalid
                );
            }
        }
    }

    /** 带结构化证据的安全前置检查异常，便于运维定位哪个条件未满足。 */
    static final class RoutingPreflightException extends IllegalStateException {
        /** 失败时采集的不可变证据。 */
        private final Map<String, Object> evidence;

        RoutingPreflightException(String message, Map<String, Object> evidence) {
            super(message);
            this.evidence = Map.copyOf(evidence);
        }

        Map<String, Object> evidence() {
            return evidence;
        }
    }
}
