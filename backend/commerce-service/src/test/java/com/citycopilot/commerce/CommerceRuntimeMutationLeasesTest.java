package com.citycopilot.commerce;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceRuntimeMutationLeasesTest {
    private StringRedisTemplate redis;
    private CommerceRedisReplicationExecutor replicatedRedis;
    private CommerceRuntimeMutationLeases leases;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        replicatedRedis = mock(CommerceRedisReplicationExecutor.class);
        leases = new CommerceRuntimeMutationLeases(
                redis,
                replicatedRedis,
                new CommerceRedisKeyspace(2, "v1"),
                10_000,
                60_000,
                1
        );
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        leases.shutdownMutationHeartbeats();
    }

    @Test
    void admissionLeaseUsesVoucherShardAndReleasesWithoutAnotherReplicatedWait() {
        when(replicatedRedis.executeReplicatedScript(
                eq("admission_mutation_acquire"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenReturn(0L);
        when(replicatedRedis.executeReplicatedScript(
                eq("admission_mutation_renew"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenReturn(0L);

        try (CommerceRuntimeMutationLeases.MutationLease lease = leases.acquireAdmission(3L)) {
            lease.renewAndAssert();
        }

        verify(replicatedRedis).executeReplicatedScript(
                eq("admission_mutation_acquire"),
                eq("commerce:{commerce:v1:2:1}:runtime:routing-gate"),
                any(DefaultRedisScript.class),
                eq(List.of(
                        "commerce:{commerce:v1:2:1}:runtime:routing-gate",
                        "commerce:{commerce:v1:2:1}:runtime:admission-leases"
                )),
                any(String[].class)
        );
        verify(redis).execute(
                any(DefaultRedisScript.class),
                eq(List.of("commerce:{commerce:v1:2:1}:runtime:admission-leases")),
                any(Object[].class)
        );
        verify(replicatedRedis, never()).executeReplicatedScript(
                eq("admission_mutation_release"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        );
    }

    @Test
    void transactionSynchronizationAssertsLeaseBeforeCommitAndReleasesAfterCompletion() {
        when(replicatedRedis.executeReplicatedScript(
                eq("runtime_mutation_acquire"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenReturn(0L);
        when(replicatedRedis.executeReplicatedScript(
                eq("runtime_mutation_renew"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenReturn(0L);
        CommerceRuntimeMutationLeases.MutationLease lease = leases.acquireMutation();
        TransactionSynchronizationManager.initSynchronization();

        leases.releaseAfterTransaction(lease);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.beforeCommit(false);

        verify(replicatedRedis).executeReplicatedScript(
                eq("runtime_mutation_renew"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        );
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(redis).execute(
                any(DefaultRedisScript.class),
                eq(List.of(CommerceRedisKeyspace.RUNTIME_MUTATION_LEASES_KEY)),
                any(Object[].class)
        );
    }

    @Test
    void drainingShardRejectsAdmissionBeforeBusinessPreparationCanStart() {
        when(replicatedRedis.executeReplicatedScript(
                eq("admission_mutation_acquire"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenReturn(11L);

        assertThatThrownBy(() -> leases.acquireAdmission(3L))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_ROUTING_DRAINING")
                );
    }

    @Test
    void admissionTopologyFailureIsAStableStructured503() {
        when(replicatedRedis.executeReplicatedScript(
                eq("admission_mutation_acquire"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenThrow(new IllegalStateException("CLUSTER REPLICAS unavailable"));

        assertThatThrownBy(() -> leases.acquireAdmission(3L))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("COMMERCE_ROUTING_UNAVAILABLE");
                            assertThat(error.status()).isEqualTo(503);
                            assertThat(error).hasCauseInstanceOf(IllegalStateException.class);
                        }
                );
    }

    @Test
    void runtimeMutationWaitFailureIsAStableStructured503() {
        when(replicatedRedis.executeReplicatedScript(
                eq("runtime_mutation_acquire"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenThrow(new IllegalStateException("WAIT timed out"));

        assertThatThrownBy(leases::acquireMutation)
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("COMMERCE_ROUTING_UNAVAILABLE");
                            assertThat(error.status()).isEqualTo(503);
                        }
                );
    }

    @Test
    void recoveryTopologyFailureIsAStableStructured503() {
        when(replicatedRedis.executeReplicatedScript(
                eq("runtime_recovery_mutation_acquire"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenThrow(new IllegalStateException("Redis master unavailable"));

        assertThatThrownBy(leases::acquireRecoveryMutation)
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("COMMERCE_ROUTING_UNAVAILABLE");
                            assertThat(error.status()).isEqualTo(503);
                        }
                );
    }

    @Test
    void renewalConfirmationFailureBecomesLeaseLost503() {
        when(replicatedRedis.executeReplicatedScript(
                eq("runtime_mutation_acquire"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenReturn(0L);
        when(replicatedRedis.executeReplicatedScript(
                eq("runtime_mutation_renew"),
                anyString(),
                any(DefaultRedisScript.class),
                anyList(),
                any(String[].class)
        )).thenThrow(new IllegalStateException("WAIT timed out"));

        try (CommerceRuntimeMutationLeases.MutationLease lease = leases.acquireMutation()) {
            assertThatThrownBy(lease::renewAndAssert)
                    .isInstanceOfSatisfying(
                            CommerceApiException.class,
                            error -> {
                                assertThat(error.code()).isEqualTo(
                                        "COMMERCE_ROUTING_MUTATION_LEASE_LOST"
                                );
                                assertThat(error.status()).isEqualTo(503);
                                assertThat(error).hasCauseInstanceOf(IllegalStateException.class);
                            }
                    );
        }
    }

    @Test
    void drainCountIncludesEveryPhysicalAdmissionShard() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(2L, 3L);

        assertThat(leases.activeAdmissionCount(new CommerceRedisKeyspace(2, "v1")))
                .isEqualTo(5L);
    }
}
