package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommerceOrderIdGeneratorTest {

    @Test
    void generatesUniqueIdsAcrossAHighConcurrencySizedBatch() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicLong sequence = new AtomicLong();
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(ignored -> sequence.incrementAndGet());

        CommerceOrderIdGenerator generator = new CommerceOrderIdGenerator(redis);
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 20_000; i++) {
            ids.add(generator.nextId());
        }

        assertThat(ids).hasSize(20_000);
    }
}
