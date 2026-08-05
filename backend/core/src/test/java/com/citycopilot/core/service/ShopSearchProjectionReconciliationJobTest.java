package com.citycopilot.core.service;

import com.citycopilot.core.service.discovery.ShopSearchProjectionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopSearchProjectionReconciliationJobTest {

    @Test
    void readyProjectionSkipsRebuild() {
        ShopSearchProjectionService projection = mock(ShopSearchProjectionService.class);
        when(projection.needsReconciliation()).thenReturn(false);

        new ShopSearchProjectionReconciliationJob(projection).reconcileIfNecessary();

        verify(projection, never()).rebuildAll();
    }

    @Test
    void staleProjectionTriggersFullRebuild() {
        ShopSearchProjectionService projection = mock(ShopSearchProjectionService.class);
        when(projection.needsReconciliation()).thenReturn(true);
        when(projection.rebuildAll()).thenReturn(12);

        new ShopSearchProjectionReconciliationJob(projection).reconcileIfNecessary();

        verify(projection).rebuildAll();
    }

    @Test
    void rebuildFailureDoesNotEscapeScheduler() {
        ShopSearchProjectionService projection = mock(ShopSearchProjectionService.class);
        when(projection.needsReconciliation()).thenReturn(true);
        doThrow(new IllegalStateException("elasticsearch unavailable"))
                .when(projection).rebuildAll();

        assertDoesNotThrow(() -> new ShopSearchProjectionReconciliationJob(projection)
                .reconcileIfNecessary());
    }
}
