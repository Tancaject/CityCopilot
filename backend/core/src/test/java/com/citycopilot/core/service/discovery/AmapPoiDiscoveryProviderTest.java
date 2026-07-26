package com.citycopilot.core.service.discovery;

import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.AmapPoiDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.service.AmapGeoService;
import com.citycopilot.core.service.IShopTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmapPoiDiscoveryProviderTest {

    @Test
    void discoverFiltersNonConsumerPoisFromAmapAroundResults() {
        AmapGeoService amapGeoService = mock(AmapGeoService.class);
        when(amapGeoService.searchAroundPoi(eq(112.997033D), eq(28.115606D), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Result.ok(List.of(
                        poi("A1", "湘潭大学西湖三期学生公寓12栋", "住宿服务;宿舍", "100000"),
                        poi("A2", "湘潭大学西湖学生公寓三期", "商务住宅相关;住宅区;宿舍", "120300"),
                        poi("A3", "瑞幸咖啡(湘潭大学北苑店)", "餐饮服务;咖啡厅", "050500")
                )));
        AmapPoiDiscoveryProvider provider = provider(amapGeoService);

        List<ShopDiscoveryItemDTO> items = provider.discover(new ShopDiscoveryQuery()
                .setX(112.997033D)
                .setY(28.115606D)
                .setCurrent(1)
                .setSize(10));

        assertEquals(1, items.size());
        assertEquals("瑞幸咖啡(湘潭大学北苑店)", items.get(0).getName());
        assertTrue(items.get(0).getTypeName().contains("咖啡厅"));
        verify(amapGeoService).searchAroundPoi(
                eq(112.997033D),
                eq(28.115606D),
                anyString(),
                eq("050000|060000|070000|080000|090000|100000"),
                anyInt(),
                anyInt()
        );
    }

    @Test
    void discoverFiltersSportsFieldsButKeepsFitnessBusinesses() {
        AmapGeoService amapGeoService = mock(AmapGeoService.class);
        when(amapGeoService.searchAroundPoi(eq(112.997033D), eq(28.115606D), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Result.ok(List.of(
                        poi("S1", "湘潭大学第三田径场", "体育休闲服务;运动场馆;田径场", "080100"),
                        poi("S2", "湘潭大学北苑小足球场", "体育休闲服务;运动场馆;足球场", "080101"),
                        poi("S3", "超级猩猩健身", "体育休闲服务;运动场馆;健身中心", "080600")
                )));
        AmapPoiDiscoveryProvider provider = provider(amapGeoService);

        List<ShopDiscoveryItemDTO> items = provider.discover(new ShopDiscoveryQuery()
                .setX(112.997033D)
                .setY(28.115606D)
                .setCurrent(1)
                .setSize(10));

        assertEquals(1, items.size());
        assertEquals("超级猩猩健身", items.get(0).getName());
        assertTrue(items.get(0).getTypeName().contains("健身中心"));
    }

    private AmapPoiDiscoveryProvider provider(AmapGeoService amapGeoService) {
        AmapPoiDiscoveryProvider provider = new AmapPoiDiscoveryProvider();
        GeoProperties geoProperties = new GeoProperties();
        geoProperties.setShopPageSize(10);
        geoProperties.setMaxShopPageSize(20);
        ReflectionTestUtils.setField(provider, "amapGeoService", amapGeoService);
        ReflectionTestUtils.setField(provider, "shopTypeService", mock(IShopTypeService.class));
        ReflectionTestUtils.setField(provider, "geoProperties", geoProperties);
        return provider;
    }

    private AmapPoiDTO poi(String id, String name, String type, String typecode) {
        return new AmapPoiDTO()
                .setId(id)
                .setName(name)
                .setType(type)
                .setTypecode(typecode)
                .setAdName("雨花区")
                .setAddress("测试路1号")
                .setX(112.997D)
                .setY(28.115D)
                .setDistance(200D);
    }
}
