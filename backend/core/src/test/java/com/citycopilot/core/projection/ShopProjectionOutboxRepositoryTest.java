package com.citycopilot.core.projection;

import com.citycopilot.core.entity.Shop;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopProjectionOutboxRepositoryTest {

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void advancesVersionAndAppendsOutboxInsideBusinessTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ShopProjectionOutboxRepository repository = new ShopProjectionOutboxRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        when(jdbc.update(contains("UPDATE shop"), eq(7L))).thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT projection_version"), eq(Long.class), eq(7L)))
                .thenReturn(4L);
        when(jdbc.update(contains("INSERT INTO shop_projection_outbox"),
                org.mockito.ArgumentMatchers.<Object>any(),
                eq(7L),
                eq(4L),
                contains("\"aggregateVersion\":4"))).thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        Shop before = new Shop().setId(7L).setTypeId(1L);
        Shop after = new Shop().setId(7L).setTypeId(2L).setGeoStatus("verified");

        long version = repository.append(before, after);

        assertEquals(4L, version);
        assertEquals(4L, after.getProjectionVersion());
        verify(jdbc).queryForObject(contains("FOR SHARE"), eq(String.class),
                eq(ShopProjectionLeaseService.CUTOVER_BARRIER));
        verify(jdbc).update(contains("INSERT INTO shop_projection_outbox"),
                org.mockito.ArgumentMatchers.<Object>any(),
                eq(7L),
                eq(4L),
                contains("\"aggregateVersion\":4"));
    }

    @Test
    void refusesNonTransactionalAppend() {
        ShopProjectionOutboxRepository repository = new ShopProjectionOutboxRepository(
                mock(JdbcTemplate.class), new ObjectMapper());

        assertThrows(IllegalStateException.class,
                () -> repository.append(null, new Shop().setId(1L)));
    }

    @Test
    void conditionalClaimLosesCleanlyToAnotherReplica() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ShopProjectionOutboxRepository repository = new ShopProjectionOutboxRepository(
                jdbc, new ObjectMapper());
        when(jdbc.queryForList(contains("SELECT id FROM shop_projection_outbox"),
                eq(Long.class), eq(10))).thenReturn(java.util.List.of(1L));
        when(jdbc.update(contains("SET status='CLAIMED'"),
                anyString(), eq("replica-a"), anyLong(), eq(1L))).thenReturn(0);

        assertTrue(repository.claimReady(10, "replica-a", 30_000_000L).isEmpty());
        verify(jdbc).queryForList(contains("'DEAD'"), eq(Long.class), eq(10));
    }

    @Test
    void failedInfrastructureWriteRemainsRetryableIndefinitely() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ShopProjectionOutboxRepository repository = new ShopProjectionOutboxRepository(
                jdbc, new ObjectMapper());
        ShopProjectionOutboxRepository.ClaimedEvent event = new ShopProjectionOutboxRepository.ClaimedEvent(
                1L, "event-1", 7L, 4L, 99, "claim-1");
        when(jdbc.update(contains("SET status='FAILED'"),
                anyLong(), anyString(), eq(1L), eq("claim-1"))).thenReturn(1);

        repository.fail(event, new IllegalStateException("search unavailable"), Duration.ofMinutes(15));

        verify(jdbc).update(contains("SET status='FAILED'"),
                eq(900L), contains("search unavailable"), eq(1L), eq("claim-1"));
    }

    @Test
    void completeFailsLoudlyAfterClaimOwnershipIsLost() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ShopProjectionOutboxRepository repository = new ShopProjectionOutboxRepository(
                jdbc, new ObjectMapper());
        ShopProjectionOutboxRepository.ClaimedEvent event = new ShopProjectionOutboxRepository.ClaimedEvent(
                1L, "event-1", 7L, 4L, 1, "claim-1");

        assertThrows(ShopProjectionOutboxRepository.ClaimLostException.class,
                () -> repository.complete(event));
    }

    @Test
    void failFailsLoudlyAfterClaimOwnershipIsLost() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ShopProjectionOutboxRepository repository = new ShopProjectionOutboxRepository(
                jdbc, new ObjectMapper());
        ShopProjectionOutboxRepository.ClaimedEvent event = new ShopProjectionOutboxRepository.ClaimedEvent(
                1L, "event-1", 7L, 4L, 1, "claim-1");

        assertThrows(ShopProjectionOutboxRepository.ClaimLostException.class,
                () -> repository.fail(event, new IllegalStateException("failure"), Duration.ofMinutes(15)));
    }
}
