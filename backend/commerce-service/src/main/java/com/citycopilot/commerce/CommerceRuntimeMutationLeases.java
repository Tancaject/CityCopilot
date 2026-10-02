package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceAdmissionOutcomePolicy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 与路由控制面原子协调的并发业务写租约管理器。
 *
 * <p>它不是把所有业务写串行化的互斥锁：每个写请求在 ZSET 中持有一个独立、会过期的成员。
 * 获取 Lua 先原子检查 ACTIVE 和运维租约再加入令牌；BeginDrain 先关闭同槽控制记录，再等待
 * 已取得的业务租约释放或过期，从而形成迁移屏障。</p>
 */
@Component
final class CommerceRuntimeMutationLeases {
    private static final Logger log = LoggerFactory.getLogger(CommerceRuntimeMutationLeases.class);
    /** 普通业务写租约的获取、释放和续租脚本。 */
    private static final DefaultRedisScript<Long> ACQUIRE_MUTATION =
            CommerceRedisScripts.longScript("lua/commerce_acquire_runtime_mutation.lua");
    /** 仅当令牌匹配时释放普通业务写租约。 */
    private static final DefaultRedisScript<Long> RELEASE_MUTATION =
            CommerceRedisScripts.longScript("lua/commerce_release_runtime_mutation.lua");
    /** 仅当令牌匹配时延长普通业务写租约。 */
    private static final DefaultRedisScript<Long> RENEW_MUTATION =
            CommerceRedisScripts.longScript("lua/commerce_renew_runtime_mutation.lua");
    /** 恢复写在 DRAINING 下仍可取得、但受运维租约约束的专用获取脚本。 */
    private static final DefaultRedisScript<Long> ACQUIRE_RECOVERY =
            CommerceRedisScripts.longScript("lua/commerce_acquire_runtime_recovery.lua");
    /** Voucher 分片内准入租约的获取脚本。 */
    private static final DefaultRedisScript<Long> ACQUIRE_ADMISSION =
            CommerceRedisScripts.longScript("lua/commerce_acquire_admission_lease.lua");
    /** 路由运维任务互斥租约的获取、释放和续租脚本。 */
    private static final DefaultRedisScript<Long> ACQUIRE_OPERATOR =
            CommerceRedisScripts.longScript("lua/commerce_acquire_routing_operator.lua");
    /** 仅当令牌匹配时释放路由运维互斥租约。 */
    private static final DefaultRedisScript<Long> RELEASE_OPERATOR =
            CommerceRedisScripts.longScript("lua/commerce_release_routing_operator.lua");
    /** 仅当令牌匹配时延长路由运维互斥租约。 */
    private static final DefaultRedisScript<Long> RENEW_OPERATOR =
            CommerceRedisScripts.longScript("lua/commerce_renew_routing_operator.lua");
    /** 清理过期 ZSET 成员并返回当前活跃业务租约数。 */
    private static final DefaultRedisScript<Long> COUNT_MUTATIONS =
            CommerceRedisScripts.longScript("lua/commerce_count_runtime_mutations.lua");

    /** 执行单键安全释放和活跃数查询。 */
    private final StringRedisTemplate redis;
    /** 让获取/续租写入达到要求的 Redis 副本数。 */
    private final CommerceRedisReplicationExecutor replicatedRedis;
    /** 提供路由控制值及同槽租约键。 */
    private final CommerceRedisKeyspace keyspace;
    /** 普通业务/恢复/准入租约的 TTL。 */
    private final long mutationTtlMillis;
    /** 一次性路由运维任务的租约 TTL。 */
    private final long operatorTtlMillis;
    /** 为并发业务租约执行独立心跳，避免请求线程阻塞导致自然过期。 */
    private final ScheduledThreadPoolExecutor mutationHeartbeats;

    CommerceRuntimeMutationLeases(
            StringRedisTemplate redis,
            CommerceRedisReplicationExecutor replicatedRedis,
            CommerceRedisKeyspace keyspace,
            @Value("${citycopilot.commerce.runtime-mutation-lease-ttl-ms:600000}")
            long mutationTtlMillis,
            @Value("${citycopilot.commerce.routing-operator-lease-ttl-ms:3600000}")
            long operatorTtlMillis,
            @Value("${citycopilot.commerce.runtime-mutation-heartbeat-threads:4}")
            int mutationHeartbeatThreads
    ) {
        if (mutationTtlMillis < 10_000 || mutationTtlMillis > 3_600_000) {
            throw new IllegalArgumentException("Commerce runtime mutation lease TTL must be 10s..1h");
        }
        if (operatorTtlMillis < 60_000 || operatorTtlMillis > 7_200_000) {
            throw new IllegalArgumentException("Commerce routing operator lease TTL must be 1m..2h");
        }
        if (mutationHeartbeatThreads < 1 || mutationHeartbeatThreads > 64) {
            throw new IllegalArgumentException(
                    "Commerce runtime mutation heartbeat threads must be 1..64"
            );
        }
        this.redis = redis;
        this.replicatedRedis = replicatedRedis;
        this.keyspace = keyspace;
        this.mutationTtlMillis = mutationTtlMillis;
        this.operatorTtlMillis = operatorTtlMillis;
        AtomicInteger threadSequence = new AtomicInteger();
        this.mutationHeartbeats = new ScheduledThreadPoolExecutor(
                mutationHeartbeatThreads,
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "commerce-runtime-mutation-heartbeat-"
                                    + threadSequence.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );
        this.mutationHeartbeats.setRemoveOnCancelPolicy(true);
        this.mutationHeartbeats.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.mutationHeartbeats.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /**
     * 在全局路由为 ACTIVE 且无运维租约时取得普通业务写租约。
     *
     * @return 已启动心跳的租约；调用方必须关闭或绑定事务完成
     * @throws CommerceApiException 正在排空、激活或控制面不可用时抛出 503
     */
    MutationLease acquireMutation() {
        String token = UUID.randomUUID().toString();
        Long result = executeBusinessLeaseScript(
                "COMMERCE_ROUTING_UNAVAILABLE",
                "Commerce mutation routing is unavailable",
                () -> replicatedRedis.executeReplicatedScript(
                        "runtime_mutation_acquire",
                        CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                        ACQUIRE_MUTATION,
                        List.of(
                                CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                                CommerceRedisKeyspace.RUNTIME_MUTATION_LEASES_KEY,
                                CommerceRedisKeyspace.ROUTING_OPERATOR_LEASE_KEY
                        ),
                        activeControlPrefix(),
                        token,
                        String.valueOf(mutationTtlMillis)
                )
        );
        if (result == null || result == 11L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_DRAINING",
                    "Commerce mutations are closed for a routing migration",
                    503
            );
        }
        if (result == 12L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_MUTATION_BUSY",
                    "Commerce routing activation is in progress",
                    503
            );
        }
        if (result != 0L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_UNAVAILABLE",
                    "Commerce mutation routing is unavailable",
                    503
            );
        }
        return startMutationLease(
                token,
                CommerceRedisKeyspace.RUNTIME_MUTATION_LEASES_KEY,
                "runtime_mutation",
                "Commerce runtime mutation"
        );
    }

    /**
     * 为库存恢复取得写租约；ACTIVE/DRAINING 均可恢复，但路由运维激活期间会被 fence。
     */
    MutationLease acquireRecoveryMutation() {
        String token = UUID.randomUUID().toString();
        Long result = executeBusinessLeaseScript(
                "COMMERCE_ROUTING_UNAVAILABLE",
                "Commerce recovery routing is unavailable",
                () -> replicatedRedis.executeReplicatedScript(
                        "runtime_recovery_mutation_acquire",
                        CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                        ACQUIRE_RECOVERY,
                        List.of(
                                CommerceRedisKeyspace.ROUTING_CONTROL_KEY,
                                CommerceRedisKeyspace.RUNTIME_MUTATION_LEASES_KEY,
                                CommerceRedisKeyspace.ROUTING_OPERATOR_LEASE_KEY
                        ),
                        activeControlPrefix(),
                        drainingControlPrefix(),
                        token,
                        String.valueOf(mutationTtlMillis)
                )
        );
        if (result == null || result == 11L || result == 12L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_MUTATION_BUSY",
                    "Commerce inventory recovery is fenced by routing operations",
                    503
            );
        }
        if (result != 0L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_UNAVAILABLE",
                    "Commerce recovery routing is unavailable",
                    503
            );
        }
        return startMutationLease(
                token,
                CommerceRedisKeyspace.RUNTIME_MUTATION_LEASES_KEY,
                "runtime_recovery_mutation",
                "Commerce inventory recovery"
        );
    }

    /**
     * 在 Voucher 所属分片的门禁仍为 ACTIVE 时取得准入租约。
     *
     * @param voucherId 用于定位分片门禁和租约 ZSET
     * @return 已启动心跳的准入租约
     */
    MutationLease acquireAdmission(long voucherId) {
        String token = UUID.randomUUID().toString();
        String gateKey = keyspace.admissionRoutingGate(voucherId);
        String leaseKey = keyspace.admissionRuntimeLeases(voucherId);
        Long result = executeBusinessLeaseScript(
                "COMMERCE_ROUTING_UNAVAILABLE",
                "Commerce admission routing is unavailable",
                () -> replicatedRedis.executeReplicatedScript(
                        "admission_mutation_acquire",
                        gateKey,
                        ACQUIRE_ADMISSION,
                        List.of(gateKey, leaseKey),
                        keyspace.activeAdmissionGateValue(),
                        token,
                        String.valueOf(mutationTtlMillis)
                )
        );
        if (result == null || result == 11L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_DRAINING",
                    "Commerce order admission is draining for a routing migration",
                    503
            );
        }
        if (result != 0L) {
            throw new CommerceApiException(
                    "COMMERCE_ROUTING_UNAVAILABLE",
                    "Commerce admission routing is unavailable",
                    503
            );
        }
        return startMutationLease(
                token,
                leaseKey,
                "admission_mutation",
                "Commerce order admission"
        );
    }

    /**
     * 把租约生命周期绑定到当前 Spring 事务：提交前续租确认，完成后释放。
     *
     * @param lease 已取得的业务写租约
     */
    void releaseAfterTransaction(MutationLease lease) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lease.close();
            throw new IllegalStateException("Commerce mutation lease requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                lease.renewAndAssert();
            }

            @Override
            public void afterCompletion(int status) {
                lease.close();
            }
        });
    }

    /**
     * 取得全局路由运维互斥租约，防止两个迁移进程同时改变控制面。
     *
     * @return 已启动心跳的运维租约
     */
    OperatorLease acquireOperator() {
        String token = UUID.randomUUID().toString();
        Long result = replicatedRedis.executeReplicatedScript(
                "routing_operator_acquire",
                CommerceRedisKeyspace.ROUTING_OPERATOR_LEASE_KEY,
                ACQUIRE_OPERATOR,
                List.of(CommerceRedisKeyspace.ROUTING_OPERATOR_LEASE_KEY),
                token,
                String.valueOf(operatorTtlMillis)
        );
        if (result == null || result != 0L) {
            throw new IllegalStateException("Another Commerce routing operator is active");
        }
        OperatorLease lease = new OperatorLease(token);
        lease.startHeartbeat();
        return lease;
    }

    /** @return 清理过期成员后的全局活跃业务写租约数 */
    long activeMutationCount() {
        return activeLeaseCount(CommerceRedisKeyspace.RUNTIME_MUTATION_LEASES_KEY);
    }

    /** @return 给定键空间所有分片的活跃准入租约总数 */
    long activeAdmissionCount(CommerceRedisKeyspace routedKeyspace) {
        long total = 0L;
        for (String leaseKey : routedKeyspace.admissionRuntimeLeaseKeys()) {
            total = Math.addExact(total, activeLeaseCount(leaseKey));
        }
        return total;
    }

    /**
     * 一个业务写请求的可续租所有权；心跳失败会被记住，后续断言必定失败。
     */
    final class MutationLease implements AutoCloseable {
        /** Redis ZSET 中的唯一成员令牌。 */
        private final String token;
        /** 保存该类业务租约的 ZSET 键。 */
        private final String leaseKey;
        /** 指标/复制操作使用的稳定名称前缀。 */
        private final String operationPrefix;
        /** 错误日志和异常消息中的业务描述。 */
        private final String description;
        /** 保证 close 幂等。 */
        private final AtomicBoolean closed = new AtomicBoolean();
        /** 首次续租失败；一旦写入便不再假定拥有租约。 */
        private final AtomicReference<RuntimeException> ownershipFailure = new AtomicReference<>();
        /** 后台心跳任务句柄。 */
        private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        private MutationLease(
                String token,
                String leaseKey,
                String operationPrefix,
                String description
        ) {
            this.token = token;
            this.leaseKey = leaseKey;
            this.operationPrefix = operationPrefix;
            this.description = description;
        }

        /**
         * 延长租约并确认令牌仍属于当前请求；失败后业务结果不得继续提交。
         */
        void renewAndAssert() {
            RuntimeException lost = ownershipFailure.get();
            if (lost != null) throw lost;
            Long result;
            try {
                result = replicatedRedis.executeReplicatedScript(
                        operationPrefix + "_renew",
                        leaseKey,
                        RENEW_MUTATION,
                        List.of(leaseKey),
                        token,
                        String.valueOf(mutationTtlMillis)
                );
            } catch (RuntimeException failure) {
                CommerceApiException mapped = leaseLost(failure);
                ownershipFailure.compareAndSet(null, mapped);
                throw mapped;
            }
            if (result == null || result != 0L) {
                CommerceApiException failure = leaseLost(null);
                ownershipFailure.compareAndSet(null, failure);
                throw failure;
            }
        }

        private CommerceApiException leaseLost(Throwable cause) {
            CommerceApiException failure = new CommerceApiException(
                    CommerceAdmissionOutcomePolicy.COMMERCE_ROUTING_MUTATION_LEASE_LOST,
                    description + " lease expired or could not be confirmed before completion",
                    503
            );
            if (cause != null) failure.initCause(cause);
            return failure;
        }

        /** 以约三分之一 TTL 的周期续租，使长事务不会静默越过迁移屏障。 */
        private void startHeartbeat() {
            long period = Math.max(1_000L, mutationTtlMillis / 3L);
            ScheduledFuture<?> scheduled = mutationHeartbeats.scheduleWithFixedDelay(() -> {
                if (closed.get() || ownershipFailure.get() != null) return;
                try {
                    renewAndAssert();
                } catch (RuntimeException failure) {
                    ownershipFailure.compareAndSet(null, failure);
                    log.error("{} lease heartbeat failed", description, failure);
                }
            }, period, period, TimeUnit.MILLISECONDS);
            if (!heartbeat.compareAndSet(null, scheduled)) {
                scheduled.cancel(false);
                throw new IllegalStateException(description + " heartbeat was already started");
            }
        }

        /** 停止心跳并以令牌匹配脚本尽力释放租约；可重复调用。 */
        @Override
        public void close() {
            ScheduledFuture<?> scheduled = heartbeat.getAndSet(null);
            if (scheduled != null) scheduled.cancel(false);
            release(
                    closed,
                    leaseKey,
                    RELEASE_MUTATION,
                    token
            );
        }
    }

    /** 服务关闭时停止全部业务租约心跳线程。 */
    @PreDestroy
    void shutdownMutationHeartbeats() {
        mutationHeartbeats.shutdownNow();
    }

    /** 构造租约并启动心跳；启动失败时立即执行幂等释放。 */
    private MutationLease startMutationLease(
            String token,
            String leaseKey,
            String operationPrefix,
            String description
    ) {
        MutationLease lease = new MutationLease(token, leaseKey, operationPrefix, description);
        try {
            lease.startHeartbeat();
            return lease;
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    /** 使用 Redis TIME 清除过期成员并读取活跃数量，供排空前置检查。 */
    private long activeLeaseCount(String leaseKey) {
        Long count = redis.execute(COUNT_MUTATIONS, List.of(leaseKey));
        if (count == null || count < 0) {
            throw new IllegalStateException(
                    "Commerce runtime mutation leases are unavailable: " + leaseKey
            );
        }
        return count;
    }

    /** 将 Redis/复制层异常映射为稳定的 Commerce 503，而保留已结构化业务异常。 */
    private Long executeBusinessLeaseScript(
            String code,
            String message,
            java.util.function.Supplier<Long> action
    ) {
        try {
            return action.get();
        } catch (CommerceApiException structured) {
            throw structured;
        } catch (RuntimeException failure) {
            CommerceApiException mapped = new CommerceApiException(code, message, 503);
            mapped.initCause(failure);
            throw mapped;
        }
    }

    /** 路由运维进程的全局互斥租约；丢失后任何控制面写都必须停止。 */
    final class OperatorLease implements AutoCloseable {
        /** Redis 中的唯一所有权令牌。 */
        private final String token;
        /** 保证运维租约关闭和释放只执行一次。 */
        private final AtomicBoolean closed = new AtomicBoolean();
        /** 首次续租失败；设置后所有权断言持续失败。 */
        private final AtomicReference<RuntimeException> ownershipFailure = new AtomicReference<>();
        /** 运维租约专用单线程心跳执行器。 */
        private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "commerce-routing-operator-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        private OperatorLease(String token) {
            this.token = token;
        }

        /** 续租并确认运维所有权仍有效。 */
        void renewAndAssert() {
            RuntimeException lost = ownershipFailure.get();
            if (lost != null) throw lost;
            Long result = replicatedRedis.executeReplicatedScript(
                    "routing_operator_renew",
                    CommerceRedisKeyspace.ROUTING_OPERATOR_LEASE_KEY,
                    RENEW_OPERATOR,
                    List.of(CommerceRedisKeyspace.ROUTING_OPERATOR_LEASE_KEY),
                    token,
                    String.valueOf(operatorTtlMillis)
            );
            if (result == null || result != 0L) {
                IllegalStateException failure = new IllegalStateException(
                        "Commerce routing operator lease ownership was lost"
                );
                ownershipFailure.compareAndSet(null, failure);
                throw failure;
            }
        }

        private void startHeartbeat() {
            long period = Math.max(1_000L, operatorTtlMillis / 3L);
            heartbeat.scheduleWithFixedDelay(() -> {
                if (closed.get() || ownershipFailure.get() != null) return;
                try {
                    renewAndAssert();
                } catch (RuntimeException failure) {
                    ownershipFailure.compareAndSet(null, failure);
                    log.error("Commerce routing operator lease heartbeat failed", failure);
                }
            }, period, period, TimeUnit.MILLISECONDS);
        }

        /** 停止心跳并以令牌匹配方式释放运维租约。 */
        @Override
        public void close() {
            heartbeat.shutdownNow();
            release(
                    closed,
                    CommerceRedisKeyspace.ROUTING_OPERATOR_LEASE_KEY,
                    RELEASE_OPERATOR,
                    token
            );
        }
    }

    /**
     * 以 Lua 比较令牌后释放，避免过期 Worker 删除新所有者的租约。
     */
    private void release(
            AtomicBoolean closed,
            String routingKey,
            DefaultRedisScript<Long> script,
            String token
    ) {
        if (!closed.compareAndSet(false, true)) return;
        try {
            Long released = redis.execute(script, List.of(routingKey), token);
            if (released == null) {
                throw new IllegalStateException("Redis lease release returned no result");
            }
        } catch (RuntimeException failure) {
            // 遗留令牌会由 Redis TIME + TTL 自动失效；清理失败不能覆盖已提交成功的业务结果。
            log.warn("Unable to release Commerce Redis runtime mutation lease", failure);
        }
    }

    private String activeControlPrefix() {
        return "v1|ACTIVE|" + keyspace.routingGeneration()
                + "|" + keyspace.physicalShardCount() + "|";
    }

    private String drainingControlPrefix() {
        return "v1|DRAINING|" + keyspace.routingGeneration()
                + "|" + keyspace.physicalShardCount() + "|";
    }

}
