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
        CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(1, "0");
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.size(keyspace.inflight(100L))).thenReturn(0L);
        when(sets.size(keyspace.inflight(101L))).thenReturn(0L);
        when(sets.size(keyspace.inflight(102L))).thenReturn(1L);
        when(values.setIfAbsent(keyspace.stock(100L), "8")).thenReturn(true);
        CommerceRedisInventoryBootstrap bootstrap = new CommerceRedisInventoryBootstrap(
                jdbc, redis, keyspace, true
        );

        assertThat(bootstrap.initializeIfFresh(100L, 8, 0, 0, 0)).isTrue();
        assertThat(bootstrap.initializeIfFresh(101L, 8, 1, 0, 0)).isFalse();
        assertThat(bootstrap.initializeIfFresh(102L, 8, 0, 0, 0)).isFalse();

        verify(values).setIfAbsent(keyspace.stock(100L), "8");
        verify(values, never()).setIfAbsent(keyspace.stock(101L), "8");
        verify(values, never()).setIfAbsent(keyspace.stock(102L), "8");
    }
}
