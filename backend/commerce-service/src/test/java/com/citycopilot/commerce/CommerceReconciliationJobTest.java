package com.citycopilot.commerce;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceReconciliationJobTest {

    @Test
    void singletonReconciliationRepublishesCampaignMetadata() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CommerceConsistencyService consistency = mock(CommerceConsistencyService.class);
        CommerceRedisCampaignMetadata metadata = mock(CommerceRedisCampaignMetadata.class);
        stubVoucherPage(jdbc, List.of(100L));
        when(consistency.snapshotForReconciliation(100L))
                .thenReturn(Map.of("consistent", true));
        CommerceReconciliationJob job = new CommerceReconciliationJob(
                jdbc,
                redis,
                consistency,
                metadata,
                JsonMapper.builderWithJackson2Defaults().build(), leader(),
                new CommerceRedisKeyspace(1, "v1"), Duration.ofDays(1)
        );

        job.reconcile();

        verify(metadata).refreshAfterCommit(100L);
    }

    @Test
    void firstConfirmedMismatchFencesBeforeIncidentStreakThreshold() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        CommerceConsistencyService consistency = mock(CommerceConsistencyService.class);
        CommerceRedisCampaignMetadata metadata = mock(CommerceRedisCampaignMetadata.class);
        stubVoucherPage(jdbc, List.of(100L));
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment("commerce:metrics:seckill:discrepancy-streak:100"))
                .thenReturn(1L);
        when(consistency.snapshotForReconciliation(100L)).thenReturn(Map.of(
                "consistent", false,
                "reconciliationFenceRequired", true
        ));
        CommerceReconciliationJob job = new CommerceReconciliationJob(
                jdbc,
                redis,
                consistency,
                metadata,
                JsonMapper.builderWithJackson2Defaults().build(), leader(),
                new CommerceRedisKeyspace(1, "v1"), Duration.ofDays(1)
        );

        job.reconcile();

        verify(consistency).ensureReconciliationFence(100L);
        verify(values).increment("commerce:metrics:seckill:discrepancy-streak:100");
    }

    @Test
    void failureForOneVoucherDoesNotSkipTheNextVoucher() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CommerceConsistencyService consistency = mock(CommerceConsistencyService.class);
        CommerceRedisCampaignMetadata metadata = mock(CommerceRedisCampaignMetadata.class);
        stubVoucherPage(jdbc, List.of(100L, 200L));
        doThrow(new IllegalStateException("metadata unavailable"))
                .when(metadata).refreshAfterCommit(100L);
        when(consistency.snapshotForReconciliation(200L))
                .thenReturn(Map.of("consistent", true));
        CommerceReconciliationJob job = new CommerceReconciliationJob(
                jdbc,
                redis,
                consistency,
                metadata,
                JsonMapper.builderWithJackson2Defaults().build(), leader(),
                new CommerceRedisKeyspace(1, "v1"), Duration.ofDays(1)
        );

        job.reconcile();

        verify(metadata).refreshAfterCommit(100L);
        verify(metadata).refreshAfterCommit(200L);
        verify(consistency).snapshotForReconciliation(200L);
        verify(redis).delete("commerce:metrics:seckill:discrepancy-streak:200");
    }

    private CommerceJobLease leader() {
        CommerceJobLease leases = mock(CommerceJobLease.class);
        CommerceJobLease.Lease lease = mock(CommerceJobLease.Lease.class);
        when(leases.acquire("stock-reconciliation")).thenReturn(Optional.of(lease));
        doAnswer(invocation -> { invocation.getArgument(0, Runnable.class).run(); return null; })
                .when(lease).runFenced(any());
        return leases;
    }

    private void stubVoucherPage(JdbcTemplate jdbc, List<Long> voucherIds) {
        when(jdbc.queryForList("""
                    SELECT voucher_id FROM commerce_seckill_voucher
                    WHERE redis_archived_at IS NULL
                      AND end_time > CURRENT_TIMESTAMP(3) - INTERVAL ? SECOND
                      AND voucher_id>?
                    ORDER BY voucher_id
                    LIMIT ?
                    """, Long.class, 86_400L, 0L, 200)).thenReturn(voucherIds);
    }
}
