package com.citycopilot.core.service.discovery;

import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopDiscoveryToolServiceTest {

    @Test
    void nearbyToolUsesLocalDiscoveryProviderAndSlicesRequestedPage() {
        LocalShopDiscoveryProvider provider = mock(LocalShopDiscoveryProvider.class);
        GeoProperties properties = new GeoProperties();
        properties.setShopPageSize(2);
        properties.setMaxShopPageSize(20);
        when(provider.discover(any())).thenReturn(List.of(item(1L), item(2L), item(3L), item(4L)));
        ShopDiscoveryToolService service = new ShopDiscoveryToolService(provider, properties);

        Result result = service.searchNearby(2, 2, 120D, 30D);

        assertTrue(result.getSuccess());
        @SuppressWarnings("unchecked")
        List<ShopDiscoveryItemDTO> items = (List<ShopDiscoveryItemDTO>) result.getData();
        assertEquals(List.of(3L, 4L), items.stream().map(ShopDiscoveryItemDTO::getId).toList());
        verify(provider).discover(any(ShopDiscoveryQuery.class));
    }

    @Test
    void nearbyToolRejectsMissingOrInvalidCoordinatePairs() {
        LocalShopDiscoveryProvider provider = mock(LocalShopDiscoveryProvider.class);
        ShopDiscoveryToolService service = new ShopDiscoveryToolService(provider, new GeoProperties());

        Result result = service.searchNearby(1, 5, 120D, null);

        assertFalse(result.getSuccess());
        assertEquals("SHOP_GEO_INVALID", result.getCode());

        Result missing = service.searchNearby(1, 5, null, null);
        assertFalse(missing.getSuccess());
        assertEquals("SHOP_GEO_INVALID", missing.getCode());
    }

    @Test
    void extremePageNumberReturnsEmptyPageWithoutOffsetOverflow() {
        LocalShopDiscoveryProvider provider = mock(LocalShopDiscoveryProvider.class);
        when(provider.discover(any())).thenReturn(List.of(item(1L)));
        ShopDiscoveryToolService service = new ShopDiscoveryToolService(provider, new GeoProperties());

        Result result = service.searchNearby(Integer.MAX_VALUE, 20, 120D, 30D);

        assertTrue(result.getSuccess());
        @SuppressWarnings("unchecked")
        List<ShopDiscoveryItemDTO> items = (List<ShopDiscoveryItemDTO>) result.getData();
        assertTrue(items.isEmpty());
    }

    private ShopDiscoveryItemDTO item(long id) {
        return new ShopDiscoveryItemDTO()
                .setId(id)
                .setSource("local")
                .setSourceId(String.valueOf(id))
                .setOrderable(true)
                .setName("Shop " + id);
    }
}
