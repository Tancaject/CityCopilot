package com.citycopilot.core.service.discovery;

import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.entity.Shop;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyShopGeoQueryAdapterTest {

    @Test
    void preservesLegacyShopShapeWhileUsingCurrentProvider() {
        LocalShopDiscoveryProvider provider = mock(LocalShopDiscoveryProvider.class);
        GeoProperties properties = new GeoProperties();
        properties.setShopPageSize(2);
        when(provider.discover(any())).thenReturn(List.of(item(1L, 10D), item(2L, 20D)));
        LegacyShopGeoQueryAdapter adapter = new LegacyShopGeoQueryAdapter(provider, properties);

        Result result = adapter.queryNearby(1, 2, 120D, 30D);

        assertTrue(result.getSuccess());
        @SuppressWarnings("unchecked")
        List<Shop> shops = (List<Shop>) result.getData();
        assertEquals(List.of(1L, 2L), shops.stream().map(Shop::getId).toList());
        assertEquals(10D, shops.get(0).getDistance());
        verify(provider).discover(any(ShopDiscoveryQuery.class));
    }

    @Test
    void rejectsInvalidCoordinatesBeforeCallingProvider() {
        LocalShopDiscoveryProvider provider = mock(LocalShopDiscoveryProvider.class);
        LegacyShopGeoQueryAdapter adapter = new LegacyShopGeoQueryAdapter(provider, new GeoProperties());

        Result result = adapter.queryNearby(1, 5, 120D, null);

        assertFalse(result.getSuccess());
        assertEquals("SHOP_GEO_INVALID", result.getCode());
    }

    @Test
    void extremePageNumberReturnsEmptyPageWithoutOffsetOverflow() {
        LocalShopDiscoveryProvider provider = mock(LocalShopDiscoveryProvider.class);
        when(provider.discover(any())).thenReturn(List.of(item(1L, 10D)));
        LegacyShopGeoQueryAdapter adapter = new LegacyShopGeoQueryAdapter(provider, new GeoProperties());

        Result result = adapter.queryNearby(Integer.MAX_VALUE, 20, 120D, 30D);

        assertTrue(result.getSuccess());
        @SuppressWarnings("unchecked")
        List<Shop> shops = (List<Shop>) result.getData();
        assertTrue(shops.isEmpty());
    }

    private ShopDiscoveryItemDTO item(long id, double distance) {
        return new ShopDiscoveryItemDTO()
                .setId(id)
                .setTypeId(1L)
                .setSource("local")
                .setSourceId(String.valueOf(id))
                .setOrderable(true)
                .setName("Shop " + id)
                .setDistance(distance);
    }
}
