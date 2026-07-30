package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceRedisInventoryBootstrapTest {

    @Test
    void initializesOnlyAHistoryFreeVoucher() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.size(CommerceOrderService.inflightKey(100L))).thenReturn(0L);
        when(sets.size(CommerceOrderService.inflightKey(101L))).thenReturn(0L);
        when(sets.size(CommerceOrderService.inflightKey(102L))).thenReturn(1L);
        when(values.setIfAbsent("commerce:seckill:stock:100", "8")).thenReturn(true);
        CommerceRedisInventoryBootstrap bootstrap = new CommerceRedisInventoryBootstrap(jdbc, redis);

        assertThat(bootstrap.initializeIfFresh(100L, 8, 0, 0, 0)).isTrue();
        assertThat(bootstrap.initializeIfFresh(101L, 8, 1, 0, 0)).isFalse();
        assertThat(bootstrap.initializeIfFresh(102L, 8, 0, 0, 0)).isFalse();

        verify(values).setIfAbsent("commerce:seckill:stock:100", "8");
        verify(values, never()).setIfAbsent("commerce:seckill:stock:101", "8");
        verify(values, never()).setIfAbsent("commerce:seckill:stock:102", "8");
    }
}
