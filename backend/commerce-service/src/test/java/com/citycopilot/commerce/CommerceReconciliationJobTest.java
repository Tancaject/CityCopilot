package com.citycopilot.commerce;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

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
                JsonMapper.builderWithJackson2Defaults().build()
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
                JsonMapper.builderWithJackson2Defaults().build()
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
                JsonMapper.builderWithJackson2Defaults().build()
        );

        job.reconcile();

        verify(metadata).refreshAfterCommit(100L);
        verify(metadata).refreshAfterCommit(200L);
        verify(consistency).snapshotForReconciliation(200L);
        verify(redis).delete("commerce:metrics:seckill:discrepancy-streak:200");
    }

    private void stubVoucherPage(JdbcTemplate jdbc, List<Long> voucherIds) {
        long highWatermark = voucherIds.get(voucherIds.size() - 1);
        when(jdbc.queryForObject("SELECT MAX(voucher_id) FROM commerce_seckill_voucher", Long.class))
                .thenReturn(highWatermark);
        when(jdbc.queryForList("""
                SELECT voucher_id FROM commerce_seckill_voucher
                WHERE voucher_id>? AND voucher_id<=?
                ORDER BY voucher_id
                LIMIT ?
                """, Long.class, 0L, highWatermark, 200)).thenReturn(voucherIds);
    }
}
