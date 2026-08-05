package com.citycopilot.commerce;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
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
 * Concurrent runtime mutation leases serialized with the routing control key.
 *
 * <p>Business writers remain concurrent: each owns one expiring ZSET member.
 * The acquisition Lua atomically checks ACTIVE and the operator lease before
 * adding a token. BeginDrain changes the same-slot control first, then waits
 * until all previously acquired members are released or expire.</p>
 */
@Component
final class CommerceRuntimeMutationLeases {
    private static final Logger log = LoggerFactory.getLogger(CommerceRuntimeMutationLeases.class);
    private static final DefaultRedisScript<Long> ACQUIRE_MUTATION =
            script("lua/commerce_acquire_runtime_mutation.lua");
    private static final DefaultRedisScript<Long> RELEASE_MUTATION =
            script("lua/commerce_release_runtime_mutation.lua");
    private static final DefaultRedisScript<Long> RENEW_MUTATION =
            script("lua/commerce_renew_runtime_mutation.lua");
    private static final DefaultRedisScript<Long> ACQUIRE_RECOVERY =
            script("lua/commerce_acquire_runtime_recovery.lua");
    private static final DefaultRedisScript<Long> ACQUIRE_ADMISSION =
            script("lua/commerce_acquire_admission_lease.lua");
    private static final DefaultRedisScript<Long> ACQUIRE_OPERATOR =
            script("lua/commerce_acquire_routing_operator.lua");
    private static final DefaultRedisScript<Long> RELEASE_OPERATOR =
            script("lua/commerce_release_routing_operator.lua");
    private static final DefaultRedisScript<Long> RENEW_OPERATOR =
            script("lua/commerce_renew_routing_operator.lua");
    private static final DefaultRedisScript<Long> COUNT_MUTATIONS =
            script("lua/commerce_count_runtime_mutations.lua");

    private final StringRedisTemplate redis;
    private final CommerceRedisReplicationExecutor replicatedRedis;
    private final CommerceRedisKeyspace keyspace;
    private final long mutationTtlMillis;
    private final long operatorTtlMillis;
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

    long activeMutationCount() {
        return activeLeaseCount(CommerceRedisKeyspace.RUNTIME_MUTATION_LEASES_KEY);
    }

    long activeAdmissionCount(CommerceRedisKeyspace routedKeyspace) {
        long total = 0L;
        for (String leaseKey : routedKeyspace.admissionRuntimeLeaseKeys()) {
            total = Math.addExact(total, activeLeaseCount(leaseKey));
        }
        return total;
    }

    final class MutationLease implements AutoCloseable {
        private final String token;
        private final String leaseKey;
        private final String operationPrefix;
        private final String description;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<RuntimeException> ownershipFailure = new AtomicReference<>();
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
                    "COMMERCE_ROUTING_MUTATION_LEASE_LOST",
                    description + " lease expired or could not be confirmed before completion",
                    503
            );
            if (cause != null) failure.initCause(cause);
            return failure;
        }

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

    @PreDestroy
    void shutdownMutationHeartbeats() {
        mutationHeartbeats.shutdownNow();
    }

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

    private long activeLeaseCount(String leaseKey) {
        Long count = redis.execute(COUNT_MUTATIONS, List.of(leaseKey));
        if (count == null || count < 0) {
            throw new IllegalStateException(
                    "Commerce runtime mutation leases are unavailable: " + leaseKey
            );
        }
        return count;
    }

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

    final class OperatorLease implements AutoCloseable {
        private final String token;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<RuntimeException> ownershipFailure = new AtomicReference<>();
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
            // A leaked token is bounded by Redis TIME + TTL. Do not replace a
            // successfully committed business result with a cleanup failure.
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

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
