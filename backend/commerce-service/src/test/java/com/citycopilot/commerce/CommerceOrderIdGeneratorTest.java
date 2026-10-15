package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommerceOrderIdGeneratorTest {

    @Test
    void servesManyUniqueIdsFromAmortizedDurableSegments() {
        SegmentFixture fixture = fixture(Map.of());
        CommerceOrderIdGenerator generator = new CommerceOrderIdGenerator(
                fixture.jdbc, fixture.transactions, 1024
        );
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 20_000; i++) {
            ids.add(generator.nextId());
        }

        assertThat(ids).hasSize(20_000);
        assertThat(fixture.counters.get("order")).isEqualTo(20_480L);
    }

    @Test
    void namespacesAndProcessSegmentsCannotOverlap() {
        SegmentFixture fixture = fixture(Map.of());
        CommerceOrderIdGenerator first = new CommerceOrderIdGenerator(
                fixture.jdbc, fixture.transactions, 1024
        );
        CommerceOrderIdGenerator second = new CommerceOrderIdGenerator(
                fixture.jdbc, fixture.transactions, 1024
        );

        long firstOrder = first.nextId();
        long secondOrder = second.nextId();
        long voucher = first.nextVoucherId();

        assertThat(firstOrder).isEqualTo(1L);
        assertThat(secondOrder).isEqualTo(1025L);
        assertThat(voucher).isEqualTo(1L);
        assertThat(fixture.counters).containsEntry("order", 2048L)
                .containsEntry("voucher", 1024L);
    }

    @Test
    void refusesToAllocatePastTheSignedBigintRange() {
        SegmentFixture fixture = fixture(Map.of("order", Long.MAX_VALUE - 10L));
        CommerceOrderIdGenerator generator = new CommerceOrderIdGenerator(
                fixture.jdbc, fixture.transactions, 1024
        );

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exhausted");
    }

    @SuppressWarnings("unchecked")
    private SegmentFixture fixture(Map<String, Long> initial) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        Map<String, Long> counters = new HashMap<>(initial);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(jdbc.queryForList(
                anyString(),
                eq(Long.class),
                any(Object[].class)
        )).thenAnswer(invocation -> {
            Object[] arguments = varargs(invocation.getArguments(), 2);
            return java.util.List.of(counters.getOrDefault(String.valueOf(arguments[0]), 0L));
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] arguments = varargs(invocation.getArguments(), 1);
            if (sql.contains("SET next_value=")) {
                counters.put(String.valueOf(arguments[1]), ((Number) arguments[0]).longValue());
            }
            return 1;
        });
        return new SegmentFixture(jdbc, transactions, counters);
    }

    private Object[] varargs(Object[] invocationArguments, int offset) {
        if (invocationArguments.length == offset + 1
                && invocationArguments[offset] instanceof Object[] nested) {
            return nested;
        }
        return Arrays.copyOfRange(invocationArguments, offset, invocationArguments.length);
    }

    private record SegmentFixture(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Map<String, Long> counters
    ) {}
}
