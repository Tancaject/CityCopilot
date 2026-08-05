package com.citycopilot.core.service.impl;

import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.projection.ShopProjectionOutboxRepository;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.citycopilot.core.service.discovery.ShopSearchProjectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ShopServiceImplGeoIndexTest {

    private ShopServiceImpl service;
    private ShopGeoIndexService shopGeoIndexService;
    private ShopSearchProjectionService shopSearchProjectionService;
    private ShopProjectionOutboxRepository projectionOutbox;

    @BeforeEach
    void setUp() {
        service = new ShopServiceImpl();
        shopGeoIndexService = mock(ShopGeoIndexService.class);
        shopSearchProjectionService = mock(ShopSearchProjectionService.class);
        projectionOutbox = mock(ShopProjectionOutboxRepository.class);
        ReflectionTestUtils.setField(service, "shopGeoIndexService", shopGeoIndexService);
        ReflectionTestUtils.setField(service, "shopSearchProjectionService", shopSearchProjectionService);
        ReflectionTestUtils.setField(service, "shopProjectionOutboxRepository", projectionOutbox);
    }

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void refreshesImmediatelyWithoutAnActiveTransaction() {
        Shop before = shop(1L, 10L, 120.10, 30.10, "pending");
        Shop after = shop(1L, 10L, 120.10, 30.10, "verified");

        service.refreshShopGeoIndexAfterCommit(before, after);

        verify(shopGeoIndexService).refreshShopGeoIndex(before, after);
        verify(shopSearchProjectionService).upsertLatestBestEffort(1L);
    }

    @Test
    void activeTransactionDefersRefreshUntilAfterCommit() {
        Shop before = shop(1L, 10L, 120.10, 30.10, "pending");
        Shop after = shop(1L, 10L, 120.10, 30.10, "verified");
        beginTransactionSynchronization();

        service.refreshShopGeoIndexAfterCommit(before, after);

        verify(shopGeoIndexService, never()).refreshShopGeoIndex(before, after);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());

        synchronizations.get(0).afterCommit();

        verify(shopGeoIndexService).refreshShopGeoIndex(before, after);
        verify(shopSearchProjectionService).upsertLatestBestEffort(1L);
    }

    @Test
    void rolledBackTransactionDoesNotRefreshIndex() {
        Shop before = shop(1L, 10L, 120.10, 30.10, "verified");
        Shop after = shop(1L, 11L, 121.20, 31.20, "verified");
        beginTransactionSynchronization();

        service.refreshShopGeoIndexAfterCommit(before, after);
        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(shopGeoIndexService, never()).refreshShopGeoIndex(before, after);
        verify(shopSearchProjectionService, never()).upsertLatestBestEffort(anyLong());
    }

    @Test
    void activeTransactionWithoutSynchronizationNeverRefreshesBeforeCommit() {
        Shop after = shop(1L, 10L, 120.10, 30.10, "verified");
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.refreshShopGeoIndexAfterCommit(null, after);

        verify(shopGeoIndexService, never()).refreshShopGeoIndex(null, after);
        verify(shopSearchProjectionService, never()).upsertLatestBestEffort(anyLong());
        verify(shopSearchProjectionService).markReconciliationRequired();
    }

    @Test
    void redisFailureAfterCommitDoesNotEscapeToBusinessCall() {
        Shop after = shop(1L, 10L, 120.10, 30.10, "verified");
        doThrow(new IllegalStateException("redis unavailable"))
                .when(shopGeoIndexService).refreshShopGeoIndex(null, after);

        assertDoesNotThrow(() -> service.refreshShopGeoIndexAfterCommit(null, after));
        verify(shopSearchProjectionService).upsertLatestBestEffort(1L);
    }

    @Test
    void searchFailureAfterCommitDoesNotEscapeAndMarksReconciliationNeeded() {
        Shop after = shop(1L, 10L, 120.10, 30.10, "verified");
        doThrow(new IllegalStateException("elasticsearch unavailable"))
                .when(shopSearchProjectionService).upsertLatestBestEffort(1L);

        assertDoesNotThrow(() -> service.refreshShopGeoIndexAfterCommit(null, after));

        verify(shopGeoIndexService).refreshShopGeoIndex(null, after);
        verify(shopSearchProjectionService).markReconciliationRequired();
    }

    @Test
    void searchProjectionReloadsLatestShopByIdAfterCommit() {
        Shop after = shop(8L, 7L, 120.10, 30.10, "verified");

        service.refreshShopGeoIndexAfterCommit(null, after);

        verify(shopSearchProjectionService).upsertLatestBestEffort(8L);
    }

    @Test
    void enabledOutboxReplacesAfterCommitDualWrite() {
        ShopProjectionProperties properties = new ShopProjectionProperties();
        properties.setOutboxEnabled(true);
        ReflectionTestUtils.setField(service, "shopProjectionProperties", properties);
        Shop before = shop(8L, 6L, 120.00, 30.00, "verified");
        Shop after = shop(8L, 7L, 120.10, 30.10, "verified");

        ReflectionTestUtils.invokeMethod(service, "recordShopChanged", before, after);

        verify(projectionOutbox).append(before, after);
        verify(shopGeoIndexService, never()).refreshShopGeoIndex(before, after);
        verify(shopSearchProjectionService, never()).upsertLatestBestEffort(anyLong());
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private Shop shop(Long id, Long typeId, Double x, Double y, String status) {
        return new Shop()
                .setId(id)
                .setTypeId(typeId)
                .setX(x)
                .setY(y)
                .setGeoStatus(status);
    }
}
