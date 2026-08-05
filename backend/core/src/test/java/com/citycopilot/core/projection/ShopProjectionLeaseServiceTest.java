package com.citycopilot.core.projection;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopProjectionLeaseServiceTest {

    @Test
    void returnsMonotonicFencingTokenAndRejectsLostLease() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("INSERT IGNORE"), eq("projection"))).thenReturn(1);
        when(jdbc.update(contains("fencing_token=fencing_token+1"),
                anyString(), anyLong(), eq("projection"))).thenReturn(1);
        when(jdbc.queryForList(contains("SELECT fencing_token"),
                eq(Long.class), eq("projection"), anyString())).thenReturn(List.of(7L));
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"),
                eq(Integer.class), eq("projection"), anyString(), eq(7L))).thenReturn(0);
        ShopProjectionLeaseService service = new ShopProjectionLeaseService(jdbc);

        ShopProjectionLeaseService.Lease lease = service.tryAcquire(
                "projection", Duration.ofSeconds(30)).orElseThrow();

        assertEquals(7L, lease.fencingToken());
        assertThrows(ShopProjectionLeaseService.ProjectionLeaseLostException.class, lease::assertHeld);
    }

    @Test
    void cutoverHoldsWriterFenceAndExclusiveOutboxBarrierInOneTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition.class)))
                .thenReturn(status);
        when(jdbc.update(contains("INSERT IGNORE"), eq("projection"))).thenReturn(1);
        when(jdbc.update(contains("fencing_token=fencing_token+1"),
                anyString(), anyLong(), eq("projection"))).thenReturn(1);
        when(jdbc.queryForList(contains("SELECT fencing_token"),
                eq(Long.class), eq("projection"), anyString())).thenReturn(List.of(7L));
        when(jdbc.queryForList(contains("FOR UPDATE"),
                eq(Long.class), eq("projection"), anyString(), eq(7L))).thenReturn(List.of(7L));
        when(jdbc.queryForList(contains("SELECT lease_name"),
                eq(String.class), eq(ShopProjectionLeaseService.CUTOVER_BARRIER)))
                .thenReturn(List.of(ShopProjectionLeaseService.CUTOVER_BARRIER));
        ShopProjectionLeaseService service = new ShopProjectionLeaseService(jdbc, transactions);
        ShopProjectionLeaseService.Lease lease = service.tryAcquire(
                "projection", Duration.ofSeconds(30)).orElseThrow();

        assertEquals(42L, lease.executeCutoverFenced(() -> 42L));

        verify(jdbc).queryForList(contains("FOR UPDATE"),
                eq(String.class), eq(ShopProjectionLeaseService.CUTOVER_BARRIER));
        verify(transactions).commit(status);
    }
}
