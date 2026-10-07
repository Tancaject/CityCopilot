package com.citycopilot.core.projection;

import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.citycopilot.core.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopProjectionOutboxProjectorTest {

    private ShopProjectionOutboxRepository outbox;
    private ShopProjectionLeaseService leases;
    private ShopProjectionLeaseService.Lease lease;
    private ShopMapper shopMapper;
    private ShopGeoIndexService geoIndex;
    private ShopProjectionOutboxProjector projector;
    private ShopProjectionOutboxRepository.ClaimedEvent event;

    @BeforeEach
    void setUp() {
        outbox = mock(ShopProjectionOutboxRepository.class);
        leases = mock(ShopProjectionLeaseService.class);
        lease = mock(ShopProjectionLeaseService.Lease.class);
        shopMapper = mock(ShopMapper.class);
        geoIndex = mock(ShopGeoIndexService.class);
        ShopProjectionProperties properties = new ShopProjectionProperties();
        properties.setMaintenanceLeaseTtl(Duration.ofMinutes(1));
        projector = new ShopProjectionOutboxProjector(
                outbox, leases, shopMapper, geoIndex, properties, mock(CacheClient.class));
        event = new ShopProjectionOutboxRepository.ClaimedEvent(
                1L, "event-1", 7L, 3L, 1, "claim-1");
        when(lease.fencingToken()).thenReturn(9L);
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));
        when(outbox.claimReady(anyInt(), anyString(), anyLong()))
                .thenReturn(List.of(event));
    }

    @Test
    void appliesLatestSourceVersionAndCompletesClaim() {
        Shop current = new Shop().setId(7L).setTypeId(2L).setProjectionVersion(5L);
        when(shopMapper.selectById(7L)).thenReturn(current);

        projector.projectReadyEvents();

        verify(geoIndex).refreshShopGeoIndexVersioned(current, 5L);
        verify(lease).assertHeld();
        verify(outbox).complete(event);
        verify(outbox, never()).fail(any(), any(), any(Duration.class));
    }

    @Test
    void leavesFailedExternalWriteRetryable() {
        Shop current = new Shop().setId(7L).setProjectionVersion(3L);
        when(shopMapper.selectById(7L)).thenReturn(current);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(geoIndex).refreshShopGeoIndexVersioned(current, 3L);

        projector.projectReadyEvents();

        verify(outbox).fail(any(), any(IllegalStateException.class), any(Duration.class));
        verify(outbox, never()).complete(any());
    }
}
