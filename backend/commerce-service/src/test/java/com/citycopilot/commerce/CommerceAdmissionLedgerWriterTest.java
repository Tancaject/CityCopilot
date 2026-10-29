package com.citycopilot.commerce;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceAdmissionLedgerWriterTest {
    private JdbcTemplate jdbc;
    private SimpleMeterRegistry metrics;
    private CommerceAdmissionLedgerWriter writer;
    private ExecutorService callers;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        metrics = new SimpleMeterRegistry();
        writer = CommerceAdmissionLedgerWriter.manual(jdbc, metrics, 64);
        callers = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        callers.shutdownNow();
    }

    @Test
    void queuedPreparesShareOneStatementOrderedByOrderId() throws Exception {
        List<Future<?>> results = List.of(
                callers.submit(() -> writer.insertPrepared(identity(303))),
                callers.submit(() -> writer.insertPrepared(identity(301))),
                callers.submit(() -> writer.insertPrepared(identity(302))));
        flushWhenQueued(3);
        for (Future<?> result : results) result.get();

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT IGNORE INTO commerce_admission_ledger"), arguments.capture());
        assertThat(arguments.getValue()).hasSize(21);
        assertThat(List.of(arguments.getValue()[0], arguments.getValue()[7], arguments.getValue()[14]))
                .containsExactly(301L, 302L, 303L);
        assertThat(metrics.get("citycopilot.commerce.admission.ledger.flush.size").summary().max()).isEqualTo(3.0);
    }

    @Test
    void acceptBatchIsCertainOnlyWhenEveryRowChanged() throws Exception {
        when(jdbc.update(contains("WHERE order_id IN"), any(Object[].class))).thenReturn(2, 3);

        List<Future<Boolean>> partial = acceptAll(301, 302, 303);
        assertThat(results(partial)).containsOnly(false);

        List<Future<Boolean>> complete = acceptAll(301, 302, 303);
        assertThat(results(complete)).containsOnly(true);
    }

    @Test
    void deadlockedBatchIsRetriedPerWriteAndFailsOnlyTheFailingCaller() throws Exception {
        when(jdbc.update(contains("WHERE order_id IN"), any(Object[].class)))
                .thenThrow(new CannotAcquireLockException("deadlock", new SQLException("Deadlock found", "40001", 1213)));
        when(jdbc.update(contains("WHERE order_id=?"), any(Object[].class))).thenAnswer(invocation -> {
            if (Long.valueOf(302L).equals(invocation.getArgument(1))) {
                throw new CannotAcquireLockException("still locked");
            }
            return 1;
        });

        List<Future<Boolean>> results = acceptAll(301, 302, 303);

        assertThat(results.get(0).get()).isTrue();
        assertThatThrownBy(() -> results.get(1).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CannotAcquireLockException.class);
        assertThat(results.get(2).get()).isTrue();
        verify(jdbc, times(3)).update(contains("WHERE order_id=?"), any(Object[].class));
    }

    @Test
    void systemicBatchFailureFailsEveryCallerWithoutPerWriteRetries() throws Exception {
        when(jdbc.update(contains("WHERE order_id IN"), any(Object[].class)))
                .thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));

        List<Future<Boolean>> results = acceptAll(301, 302, 303);

        for (Future<Boolean> result : results) {
            assertThatThrownBy(result::get).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CannotGetJdbcConnectionException.class);
        }
        // 取连接失败是系统性的，逐条重做只会让每个调用方再等一轮超时。
        verify(jdbc, never()).update(contains("WHERE order_id=?"), any(Object[].class));
    }

    @Test
    void duplicateIdentityInOneBatchIsNeverReportedAsCertain() throws Exception {
        when(jdbc.update(contains("WHERE order_id=?"), any(Object[].class))).thenReturn(1);

        List<Future<Boolean>> results = acceptAll(301, 301);

        assertThat(results(results)).containsOnly(false);
        verify(jdbc).update(contains("WHERE order_id=?"), eq(301L), eq(200L), eq(100L), eq("request-301"),
                eq("seckill:v1:100"), eq("v7"), eq(4));
    }

    private List<Future<Boolean>> acceptAll(long... orderIds) throws InterruptedException {
        List<Future<Boolean>> results = new java.util.ArrayList<>();
        for (long orderId : orderIds) results.add(callers.submit(() -> writer.markAccepted(identity(orderId))));
        flushWhenQueued(orderIds.length);
        return results;
    }

    private List<Boolean> results(List<Future<Boolean>> futures) throws Exception {
        List<Boolean> values = new java.util.ArrayList<>();
        for (Future<Boolean> future : futures) values.add(future.get());
        return values;
    }

    private void flushWhenQueued(int count) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (writer.pending() < count) {
            if (System.nanoTime() > deadline) throw new AssertionError("writes were not queued");
            Thread.sleep(5);
        }
        writer.flushPending();
    }

    private static CommerceAdmissionLedgerWriter.Identity identity(long orderId) {
        return new CommerceAdmissionLedgerWriter.Identity(orderId, 200L, "request-" + orderId, 100L,
                "seckill:v1:100", "v7", 4);
    }
}
