package com.citycopilot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopGeoIndexReconciliationJobTest {

    @Test
    void skipsRebuildWhenIndexGenerationIsReady() {
        ShopGeoIndexService indexService = mock(ShopGeoIndexService.class);
        when(indexService.isReady()).thenReturn(true);

        new ShopGeoIndexReconciliationJob(indexService).reconcileIfNecessary();

        verify(indexService, never()).syncShopGeoIndex();
    }

    @Test
    void rebuildsUnreadyIndexAndKeepsFallbackAvailableOnFailure() {
        ShopGeoIndexService indexService = mock(ShopGeoIndexService.class);
        when(indexService.isReady()).thenReturn(false);
        when(indexService.syncShopGeoIndex())
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertDoesNotThrow(() ->
                new ShopGeoIndexReconciliationJob(indexService).reconcileIfNecessary());
        verify(indexService).syncShopGeoIndex();
    }
}
