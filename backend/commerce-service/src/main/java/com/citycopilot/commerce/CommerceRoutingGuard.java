package com.citycopilot.commerce;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 版本化 Commerce Redis 键空间的失败关闭路由守卫。
 *
 * <p>全局控制记录协调全部 API 实例；每个逻辑分片还有一个与 Voucher 键同槽的门禁，准入 Lua
 * 会再次校验它，从而封住“API 读到 ACTIVE 后，运维任务立即开始排空”的竞态窗口。</p>
 */
@Component
final class CommerceRoutingGuard {
    private static final Logger log = LoggerFactory.getLogger(CommerceRoutingGuard.class);

    /** 读取全局控制记录和分片门禁。 */
    private final StringRedisTemplate redis;
    /** 本实例配置的代际、分片数及门禁值。 */
    private final CommerceRedisKeyspace keyspace;
    /** 是否允许开发环境在控制记录缺失时自动初始化。 */
    private final boolean autoInitialize;

    CommerceRoutingGuard(
            StringRedisTemplate redis,
            CommerceRedisKeyspace keyspace,
            @Value("${citycopilot.commerce.routing-control-auto-initialize:true}")
            boolean autoInitialize
    ) {
        this.redis = redis;
        this.keyspace = keyspace;
        this.autoInitialize = autoInitialize;
    }

    /**
     * 开发环境首次启动时建立 ACTIVE 控制记录和缺失的分片门禁。
     *
     * <p>初始化异常不阻止进程启动，便于 readiness 暴露故障；业务准入仍会失败关闭。</p>
     */
    @PostConstruct
    void initializeLocalRoutingForDevelopment() {
        if (!autoInitialize) return;
        try {
            ValueOperations<String, String> values = redis.opsForValue();
            RoutingSnapshot current = parse(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
            boolean shardGatesInitialized = false;
            if (current == null) {
                initializeMissingShardGates(values);
                shardGatesInitialized = true;
                values.setIfAbsent(
                        CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                        keyspace.routingControlValue("ACTIVE", newRevision())
                );
                current = parse(values.get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
            }
            if (current != null && current.isActiveFor(keyspace) && !shardGatesInitialized) {
                initializeMissingShardGates(values);
            } else if (current != null && !current.matches(keyspace)) {
                log.error(
                        "Commerce routing control belongs to generation {} with {} shards; "
                                + "local generation {} with {} shards remains fail-closed",
                        current.generation(),
                        current.physicalShardCount(),
                        keyspace.routingGeneration(),
                        keyspace.physicalShardCount()
                );
            }
        } catch (RuntimeException failure) {
            // 保持进程可观测，让 readiness 报告控制面故障；所有准入仍会失败关闭。
            log.error("Unable to initialize Commerce routing control; admission remains closed", failure);
        }
    }

    /**
     * 校验指定 Voucher 当前可接受新订单。
     *
     * @param voucherId Voucher ID，用于定位分片门禁
     * @throws CommerceApiException 控制面不可读、代际不匹配或正在排空时抛出 503
     */
    void requireAdmissionActive(long voucherId) {
        if (voucherId <= 0) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "voucherId must be positive",
                    400
            );
        }
        RoutingSnapshot current;
        try {
            current = parse(redis.opsForValue().get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (current == null) {
            throw unavailable();
        }
        if (!current.matches(keyspace)) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_CONFIG_MISMATCH",
                    "This Commerce replica does not own the active Redis routing generation",
                    503
            );
        }
        if (current.state() == RoutingState.DRAINING) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_DRAINING",
                    "Commerce order admission is draining for a routing migration",
                    503
            );
        }
        try {
            // API 层检查后，准入 Lua 还会在 Voucher slot 内再次检查同一个分片门禁。
            String shardGate = redis.opsForValue().get(keyspace.admissionRoutingGate(voucherId));
            if (!keyspace.activeAdmissionGateValue().equals(shardGate)) {
                throw new CommerceApiException(
                        "COMMERCE_ROUTING_NOT_ACTIVE",
                        "Commerce order admission is not active for this Redis shard",
                        503
                );
            }
        } catch (CommerceApiException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    /**
     * 校验非准入业务写（Voucher、订单流转、运维修复）可在当前 ACTIVE 代际执行。
     *
     * <p>所有分片门禁都必须与全局状态一致，避免迁移进行到一半时继续写入。</p>
     */
    void requireRuntimeMutationActive() {
        RoutingSnapshot current;
        try {
            current = parse(redis.opsForValue().get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (current == null) throw unavailable();
        if (!current.matches(keyspace)) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_CONFIG_MISMATCH",
                    "This Commerce replica does not own the active Redis routing generation",
                    503
            );
        }
        if (current.state() != RoutingState.ACTIVE) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_DRAINING",
                    "Commerce mutations are draining for a routing migration",
                    503
            );
        }
        try {
            if (!localShardGatesMatch(RoutingState.ACTIVE)) {
                throw new CommerceApiException(
                        "COMMERCE_ROUTING_NOT_ACTIVE",
                        "Commerce mutation routing is not active on every Redis shard",
                        503
                );
            }
        } catch (CommerceApiException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    /** @return 当前全局路由快照；控制键不存在时返回 {@code null} */
    RoutingSnapshot current() {
        return parse(redis.opsForValue().get(CommerceRedisKeyspace.ROUTING_CONTROL_KEY));
    }

    /**
     * 检查本地配置的每个分片门禁是否都处于期望状态。
     *
     * @param state 期望的 ACTIVE 或 DRAINING 状态
     */
    boolean localShardGatesMatch(RoutingState state) {
        String expected = state == RoutingState.ACTIVE
                ? keyspace.activeAdmissionGateValue()
                : keyspace.drainingAdmissionGateValue();
        ValueOperations<String, String> values = redis.opsForValue();
        for (String gate : keyspace.admissionRoutingGates()) {
            if (!expected.equals(values.get(gate))) return false;
        }
        return true;
    }

    /** @return 快照的代际和分片数是否由本实例拥有 */
    boolean isLocal(RoutingSnapshot snapshot) {
        return snapshot != null && snapshot.matches(keyspace);
    }

    /** 只补齐缺失门禁，不覆盖运维任务已经设置的 DRAINING 值。 */
    private void initializeMissingShardGates(ValueOperations<String, String> values) {
        String active = keyspace.activeAdmissionGateValue();
        for (String gate : keyspace.admissionRoutingGates()) {
            values.setIfAbsent(gate, active);
        }
    }

    /**
     * 解析持久化的 {@code v1|state|generation|shards|revision} 控制记录。
     *
     * @param raw Redis 原始值
     * @return 结构化快照；空值返回 {@code null}
     * @throws IllegalStateException 格式或字段非法时抛出，调用方不得猜测默认路由
     */
    static RoutingSnapshot parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] fields = raw.split("\\|", -1);
        if (fields.length != 5 || !"v1".equals(fields[0])) {
            throw new IllegalStateException("Commerce routing control record is malformed");
        }
        RoutingState state;
        try {
            state = RoutingState.valueOf(fields[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Commerce routing control state is invalid", invalid);
        }
        int shardCount;
        try {
            shardCount = Integer.parseInt(fields[3]);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("Commerce routing shard count is invalid", invalid);
        }
        if (fields[2].isBlank()
                || shardCount < 1
                || shardCount > 1024
                || fields[4].isBlank()) {
            throw new IllegalStateException("Commerce routing control record is invalid");
        }
        return new RoutingSnapshot(state, fields[2], shardCount, fields[4]);
    }

    private CommerceApiException unavailable() {
        return new CommerceApiException(
                "COMMERCE_ROUTING_UNAVAILABLE",
                "Commerce routing control is unavailable; order admission is closed",
                503
        );
    }

    private String newRevision() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 路由控制面的两个安全状态。 */
    enum RoutingState {
        /** 允许业务写和准入。 */
        ACTIVE,
        /** 已关闭新写，等待在途任务清空。 */
        DRAINING
    }

    /**
     * 全局路由控制记录的不可变快照。
     *
     * @param state 当前状态
     * @param generation Redis 键空间代际
     * @param physicalShardCount 逻辑物理分片数
     * @param revision 本次控制变更的唯一修订号
     */
    record RoutingSnapshot(
            RoutingState state,
            String generation,
            int physicalShardCount,
            String revision
    ) {
        RoutingSnapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(revision, "revision");
        }

        /** @return 快照指纹是否与给定键空间配置一致 */
        boolean matches(CommerceRedisKeyspace keyspace) {
            return generation.equals(keyspace.routingGeneration())
                    && physicalShardCount == keyspace.physicalShardCount();
        }

        /** @return 快照是否既属于给定键空间又处于 ACTIVE */
        boolean isActiveFor(CommerceRedisKeyspace keyspace) {
            return state == RoutingState.ACTIVE && matches(keyspace);
        }
    }
}
