package com.citycopilot.core.service.discovery;

import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.tool.CoreToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
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
    void nearbyToolPassesPreferredCategoryToLocalDiscovery() {
        LocalShopDiscoveryProvider provider = mock(LocalShopDiscoveryProvider.class);
        when(provider.discover(any())).thenReturn(List.of());
        ShopDiscoveryToolService service = new ShopDiscoveryToolService(provider, new GeoProperties());

        Result result = service.searchNearby(1, 5, 120D, 30D, "火锅");

        assertTrue(result.getSuccess());
        ArgumentCaptor<ShopDiscoveryQuery> query = ArgumentCaptor.forClass(ShopDiscoveryQuery.class);
        verify(provider).discover(query.capture());
        assertEquals("火锅", query.getValue().getPreferredCategory());
    }

    @Test
    void nearbyToolPublishesPreferredCategoryAsOptionalCatalogParameter() {
        LocalShopDiscoveryProvider provider = mock(LocalShopDiscoveryProvider.class);
        ShopDiscoveryToolService service = new ShopDiscoveryToolService(provider, new GeoProperties());
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeanDefinitionNames()).thenReturn(new String[]{"shopDiscoveryToolService"});
        doReturn(ShopDiscoveryToolService.class).when(context).getType("shopDiscoveryToolService");
        when(context.getBean("shopDiscoveryToolService")).thenReturn(service);
        CoreToolRegistry registry = new CoreToolRegistry(context);

        registry.afterSingletonsInstantiated();

        var definition = registry.definitions().stream()
                .filter(tool -> "search_nearby_shops".equals(tool.name()))
                .findFirst()
                .orElseThrow();
        var preferredCategory = definition.parameters().stream()
                .filter(parameter -> "preferredCategory".equals(parameter.name()))
                .findFirst()
                .orElseThrow();
        assertFalse(preferredCategory.required());
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
