package com.citycopilot.commerce;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceConsistencyServiceTest {

    @Test
    void globalTrafficForAnotherVoucherDoesNotChangeEvidenceOrBlockRepair() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "5", List.of(10L, 20L), Set.of("10", "20"), 0);
        fixture.globalHealth.set(Map.of(
                "scope", "global",
                "available", true,
                "pending", 17L,
                "drained", false
        ));

        Map<String, Object> before = fixture.service.snapshot(100L);
        fixture.globalHealth.set(Map.of(
                "scope", "global",
                "available", true,
                "pending", 23L,
                "drained", false
        ));
        Map<String, Object> withMoreGlobalTraffic = fixture.service.snapshot(100L);

        assertThat(before.get("consistent")).isEqualTo(true);
        assertThat(before.get("evidenceHash"))
                .isEqualTo("204cc31d019e642b2c35cd05ded11bcf9b52114fca4e13da004133ca2cde9ef2");
        assertThat(withMoreGlobalTraffic.get("consistent")).isEqualTo(true);
        assertThat(withMoreGlobalTraffic.get("evidenceHash")).isEqualTo(before.get("evidenceHash"));
        assertThat(fixture.service.repair(100L, String.valueOf(before.get("evidenceHash"))).verified()).isTrue();
        assertThat(fixture.globalHealthReads).hasValue(2);
    }

    @Test
    void sameBuyerCountWithDifferentMembersIsInconsistent() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "5", List.of(10L, 20L), Set.of("10", "30"), 0);

        Map<String, Object> snapshot = fixture.service.snapshot(100L);

        assertThat(snapshot)
                .containsEntry("databaseBuyerCount", 2)
                .containsEntry("redisBuyerCount", 2)
                .containsEntry("consistent", false);
        assertThat(snapshot.get("missingRedisBuyers")).isEqualTo(List.of("20"));
        assertThat(snapshot.get("unexpectedRedisBuyers")).isEqualTo(List.of("30"));
        assertThat(snapshot.get("databaseBuyerHash")).isNotEqualTo(snapshot.get("redisBuyerHash"));
        assertThat(snapshot).containsEntry("reconciliationFenceRequired", true);
    }

    @Test
    void reconciliationFenceUsesStableTokenAndReplicaConfirmation() {
        Fixture fixture = new Fixture();

        fixture.service.ensureReconciliationFence(100L);

        verify(fixture.replicatedRedis).setIfAbsent(
                "reconciliation_inventory_fence",
                CommerceRedisKeyspace.FENCE_PREFIX + 100L,
                CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN
        );
    }

    @Test
    void repairNeverOverwritesAnotherMaintenanceFenceOwner() {
        Fixture fixture = new Fixture();
        when(fixture.replicatedRedis.setIfAbsent(
                eq("consistency_repair_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString()
        )).thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT);
        when(fixture.values.get(CommerceRedisKeyspace.FENCE_PREFIX + 100L))
                .thenReturn("voucher-admin-update:v1");

        assertThatThrownBy(() -> fixture.service.repair(100L, "evidence"))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_INVENTORY_MAINTENANCE")
                );

        verify(fixture.replicatedRedis, never()).compareAndSet(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
        verify(fixture.replicatedRedis, never()).deleteIfEquals(
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void staleEvidenceLeavesAutomaticReconciliationFenceUntouched() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "5", List.of(10L), Set.of("10"), 0);
        Map<String, Object> before = fixture.service.snapshot(100L);
        when(fixture.replicatedRedis.setIfAbsent(
                eq("consistency_repair_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString()
        )).thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT);
        when(fixture.values.get(CommerceRedisKeyspace.FENCE_PREFIX + 100L))
                .thenReturn(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN);
        when(fixture.values.get(CommerceRedisKeyspace.STOCK_PREFIX + 100L)).thenReturn("4");

        assertThatThrownBy(() -> fixture.service.repair(
                100L,
                String.valueOf(before.get("evidenceHash"))
        )).isInstanceOfSatisfying(
                CommerceApiException.class,
                error -> assertThat(error.code()).isEqualTo("COMMERCE_STALE_EVIDENCE")
        );

        verify(fixture.replicatedRedis, never()).deleteIfEquals(
                anyString(),
                anyString(),
                anyString()
        );
        verify(fixture.replicatedRedis, never()).compareAndSet(
                eq("consistency_repair_takeover"),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void staleEvidencePromotesNewRepairFenceToPersistentReconciliationFence() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "5", List.of(10L), Set.of("10"), 0);
        Map<String, Object> before = fixture.service.snapshot(100L);
        when(fixture.values.get(CommerceRedisKeyspace.STOCK_PREFIX + 100L)).thenReturn("4");
        when(fixture.replicatedRedis.compareAndSet(
                eq("consistency_repair_restore_reconciliation_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString(),
                eq(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN)
        )).thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CHANGED);

        assertThatThrownBy(() -> fixture.service.repair(
                100L,
                String.valueOf(before.get("evidenceHash"))
        )).isInstanceOfSatisfying(
                CommerceApiException.class,
                error -> assertThat(error.code()).isEqualTo("COMMERCE_STALE_EVIDENCE")
        );

        verify(fixture.replicatedRedis).setIfAbsent(
                eq("consistency_repair_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString()
        );
        verify(fixture.replicatedRedis).compareAndSet(
                eq("consistency_repair_restore_reconciliation_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString(),
                eq(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN)
        );
        verify(fixture.replicatedRedis, never()).deleteIfEquals(
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void failedRepairRestoresAutomaticFenceAndNeverReleasesIt() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "4", List.of(10L), Set.of("10"), 0);
        Map<String, Object> before = fixture.service.snapshot(100L);
        when(fixture.replicatedRedis.setIfAbsent(
                eq("consistency_repair_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString()
        )).thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT);
        when(fixture.values.get(CommerceRedisKeyspace.FENCE_PREFIX + 100L))
                .thenReturn(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN);
        when(fixture.replicatedRedis.compareAndSet(
                eq("consistency_repair_takeover"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                eq(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN),
                anyString()
        )).thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CHANGED);
        when(fixture.replicatedRedis.compareAndSet(
                eq("consistency_repair_restore_reconciliation_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString(),
                eq(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN)
        )).thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CHANGED);

        assertThatThrownBy(() -> fixture.service.repair(
                100L,
                String.valueOf(before.get("evidenceHash"))
        )).isInstanceOfSatisfying(
                CommerceApiException.class,
                error -> assertThat(error.code()).isEqualTo("COMMERCE_REPAIR_VERIFICATION_FAILED")
        );

        verify(fixture.replicatedRedis).compareAndSet(
                eq("consistency_repair_restore_reconciliation_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString(),
                eq(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN)
        );
        verify(fixture.replicatedRedis, never()).deleteIfEquals(
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void successfulVerifiedRepairDeletesTakenOverAutomaticFence() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "4", List.of(10L), Set.of("10", "20"), 0);
        when(fixture.values.get(CommerceRedisKeyspace.STOCK_PREFIX + 100L))
                .thenReturn("4", "4", "4", "5", "5");
        when(fixture.sets.members(CommerceRedisKeyspace.BUYER_PREFIX + 100L))
                .thenReturn(
                        Set.of("10", "20"),
                        Set.of("10", "20"),
                        Set.of("10", "20"),
                        Set.of("10"),
                        Set.of("10")
                );
        Map<String, Object> before = fixture.service.snapshot(100L);
        when(fixture.replicatedRedis.setIfAbsent(
                eq("consistency_repair_fence"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString()
        )).thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CONFLICT);
        when(fixture.values.get(CommerceRedisKeyspace.FENCE_PREFIX + 100L))
                .thenReturn(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN);
        when(fixture.replicatedRedis.compareAndSet(
                eq("consistency_repair_takeover"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                eq(CommerceConsistencyService.RECONCILIATION_FENCE_TOKEN),
                anyString()
        )).thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CHANGED);

        var result = fixture.service.repair(
                100L,
                String.valueOf(before.get("evidenceHash"))
        );

        assertThat(result.verified()).isTrue();
        verify(fixture.replicatedRedis).deleteIfEquals(
                eq("consistency_repair_release"),
                eq(CommerceRedisKeyspace.FENCE_PREFIX + 100L),
                anyString()
        );
        verify(fixture.replicatedRedis, never()).compareAndSet(
                eq("consistency_repair_restore_reconciliation_fence"),
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void changedVoucherEvidenceRejectsRepair() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "5", List.of(10L), Set.of("10"), 0);
        Map<String, Object> before = fixture.service.snapshot(100L);
        when(fixture.values.get(CommerceRedisKeyspace.STOCK_PREFIX + 100L)).thenReturn("4");

        assertThatThrownBy(() -> fixture.service.repair(
                100L,
                String.valueOf(before.get("evidenceHash"))
        ))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_STALE_EVIDENCE")
                );
    }

    @Test
    void targetVoucherInFlightAdmissionBlocksRepair() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "4", List.of(10L), Set.of("10", "20"), 1);
        Map<String, Object> before = fixture.service.snapshot(100L);

        assertThat(before).containsEntry("reconciliationFenceRequired", false);

        assertThatThrownBy(() -> fixture.service.repair(
                100L,
                String.valueOf(before.get("evidenceHash"))
        ))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_VOUCHER_IN_FLIGHT")
                );
    }

    @Test
    void nonterminalInventoryRestoreBlocksRepairEvenWhenStreamInflightIsZero() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "5", List.of(10L), Set.of("10"), 0);
        fixture.recoveryRows.set(List.of(Map.of(
                "source_id", "transition:300:cancel:0",
                "source_type", "STATE_TRANSITION",
                "order_id", 300L,
                "user_id", 20L,
                "phase", "RESTORE_PENDING",
                "compensation_delta", 1
        )));
        Map<String, Object> before = fixture.service.snapshot(100L);

        assertThat(before)
                .containsEntry("activeRecoveryCount", 1)
                .containsEntry("consistent", false);
        assertThatThrownBy(() -> fixture.service.repair(
                100L,
                String.valueOf(before.get("evidenceHash"))
        ))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_RECOVERY_IN_PROGRESS")
                );
    }

    @Test
    void successfulRepairClosesVoucherRecoveryRows() {
        Fixture fixture = new Fixture();
        fixture.stubVoucher(100L, 5, "4", List.of(10L), Set.of("10", "20"), 0);
        Map<String, Object> repairRow = Map.of(
                "source_id", "1720000000000-0",
                "source_type", "STREAM_ORDER",
                "order_id", 300L,
                "user_id", 20L,
                "phase", "REPAIR_REQUIRED",
                "compensation_delta", 1
        );
        fixture.recoveryRows.set(List.of(repairRow));
        when(fixture.values.get(CommerceRedisKeyspace.STOCK_PREFIX + 100L))
                .thenReturn("4", "4", "5", "5");
        when(fixture.sets.members(CommerceRedisKeyspace.BUYER_PREFIX + 100L))
                .thenReturn(
                        Set.of("10", "20"),
                        Set.of("10", "20"),
                        Set.of("10"),
                        Set.of("10")
                );
        when(fixture.jdbc.update(
                contains("SET phase='RESTORED'"),
                eq(100L)
        )).thenAnswer(invocation -> {
            fixture.recoveryRows.set(List.of());
            return 1;
        });

        Map<String, Object> before = fixture.service.snapshot(100L);
        var result = fixture.service.repair(
                100L,
                String.valueOf(before.get("evidenceHash"))
        );

        assertThat(before)
                .containsEntry("repairRequiredRecoveryCount", 1)
                .containsEntry("repairRequiredCompensationDelta", 1)
                .containsEntry("consistent", false);
        assertThat(result.restoredRecoveryRows()).isEqualTo(1);
        assertThat(result.verified()).isTrue();
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        private final SetOperations<String, String> sets = mock(SetOperations.class);
        private final AtomicReference<Map<String, Object>> globalHealth =
                new AtomicReference<>(Map.of("scope", "global", "available", false));
        private final AtomicReference<List<Map<String, Object>>> recoveryRows =
                new AtomicReference<>(List.of());
        private final AtomicInteger globalHealthReads = new AtomicInteger();
        private final CommerceRedisReplicationExecutor replicatedRedis =
                mock(CommerceRedisReplicationExecutor.class);
        private final CommerceConsistencyService service;

        private Fixture() {
            when(redis.opsForValue()).thenReturn(values);
            when(redis.opsForSet()).thenReturn(sets);
            when(redis.execute(
                    any(RedisScript.class),
                    anyList(),
                    any(Object[].class)
            )).thenReturn(5L);
            when(replicatedRedis.setIfAbsent(anyString(), anyString(), anyString()))
                    .thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CHANGED);
            when(replicatedRedis.deleteIfEquals(anyString(), anyString(), anyString()))
                    .thenReturn(CommerceRedisReplicationExecutor.ReplicatedMutation.CHANGED);
            service = new CommerceConsistencyService(
                    jdbc,
                    redis,
                    JsonMapper.builderWithJackson2Defaults().build(),
                    new CommerceRedisKeyspace(1, "0"),
                    replicatedRedis
            ) {
                @Override
                public Map<String, Object> globalStreamHealth() {
                    globalHealthReads.incrementAndGet();
                    return globalHealth.get();
                }
            };
        }

        private void stubVoucher(
                long voucherId,
                int databaseStock,
                String redisStock,
                List<Long> databaseBuyers,
                Set<String> redisBuyers,
                long inFlight
        ) {
            when(jdbc.queryForObject(
                    contains("SELECT stock FROM commerce_seckill_voucher"),
                    eq(Integer.class),
                    eq(voucherId)
            )).thenReturn(databaseStock);
            when(jdbc.queryForList(
                    contains("SELECT user_id FROM commerce_order"),
                    eq(Long.class),
                    eq(voucherId),
                    eq(voucherId)
            )).thenReturn(databaseBuyers);
            when(jdbc.queryForList(
                    contains("SELECT r.order_id"),
                    eq(voucherId)
            )).thenReturn(List.of());
            when(jdbc.queryForList(
                    contains("FROM commerce_order_recovery"),
                    eq(voucherId)
            )).thenAnswer(invocation -> recoveryRows.get());
            when(values.get(CommerceRedisKeyspace.STOCK_PREFIX + voucherId)).thenReturn(redisStock);
            when(sets.size(CommerceRedisKeyspace.INFLIGHT_PREFIX + voucherId)).thenReturn(inFlight);
            when(sets.members(CommerceRedisKeyspace.BUYER_PREFIX + voucherId)).thenReturn(redisBuyers);
        }
    }
}
