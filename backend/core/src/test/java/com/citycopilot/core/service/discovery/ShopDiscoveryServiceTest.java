package com.citycopilot.core.service.discovery;

import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopDiscoveryServiceTest {

    @Test
    void discoverKeepsLocalShopWhenAmapPoiDuplicatesIt() {
        ShopDiscoveryService service = discoveryService(
                List.of(local("1", "amap-101", "Coffee Bar", "West Lake Road", 120.0)),
                List.of(amap("amap-101", "Coffee Bar", "West Lake Road", 90.0))
        );

        Result result = service.discover(query(1, 10));

        assertTrue(result.getSuccess());
        List<ShopDiscoveryItemDTO> items = items(result);
        assertEquals(1, items.size());
        assertEquals("local", items.get(0).getSource());
        assertEquals(1L, items.get(0).getId());
        assertTrue(items.get(0).isOrderable());
    }

    @Test
    void discoverKeepsLocalDuplicateEvenWhenAmapDistanceIsCloser() {
        ShopDiscoveryService service = discoveryService(
                List.of(local("1", "amap-101", "Coffee Bar", "West Lake Road", 1800.0)),
                List.of(amap("amap-101", "Coffee Bar", "West Lake Road", 100.0))
        );

        Result result = service.discover(query(1, 10));

        List<ShopDiscoveryItemDTO> items = items(result);
        assertEquals(1, items.size());
        assertEquals("local", items.get(0).getSource());
        assertEquals(1L, items.get(0).getId());
    }

    @Test
    void discoverPrefersLocalWithinSameDistanceBandButStillHonorsDistance() {
        ShopDiscoveryService service = discoveryService(
                List.of(
                        local("2", null, "Local Far", "Far Road", 1800.0),
                        local("3", null, "Local Near", "Near Road", 420.0)
                ),
                List.of(
                        amap("amap-close", "Amap Closest", "Close Road", 100.0),
                        amap("amap-same-band", "Amap Same Band", "Near Road", 380.0)
                )
        );

        Result result = service.discover(query(1, 10));

        List<ShopDiscoveryItemDTO> items = items(result);
        assertEquals("amap-close", items.get(0).getSourceId());
        assertEquals("3", items.get(1).getSourceId());
        assertEquals("amap-same-band", items.get(2).getSourceId());
        assertEquals("2", items.get(3).getSourceId());
    }

    @Test
    void discoverPaginatesAfterMergeAndSort() {
        ShopDiscoveryService service = discoveryService(
                List.of(
                        local("1", null, "Local One", "A", 100.0),
                        local("2", null, "Local Two", "B", 600.0)
                ),
                List.of(
                        amap("amap-1", "Amap One", "C", 1200.0),
                        amap("amap-2", "Amap Two", "D", 1800.0)
                )
        );

        Result result = service.discover(query(2, 2));

        List<ShopDiscoveryItemDTO> items = items(result);
        assertEquals(2, items.size());
        assertEquals("amap-1", items.get(0).getSourceId());
        assertEquals("amap-2", items.get(1).getSourceId());
    }

    @Test
    void discoverPreservesSearchRelevanceOrderThroughFinalMergeSort() {
        ShopDiscoveryItemDTO lowerRelevanceButCloser = local(
                "1", null, "Alphabetically First", "A", 50D
        ).setSearchRank(1);
        ShopDiscoveryItemDTO higherRelevanceButFarther = local(
                "2", null, "Alphabetically Last", "B", 800D
        ).setSearchRank(0);
        ShopDiscoveryService service = discoveryService(
                List.of(lowerRelevanceButCloser, higherRelevanceButFarther),
                List.of()
        );

        Result result = service.discover(query(1, 10));

        assertEquals(List.of(2L, 1L), items(result).stream()
                .map(ShopDiscoveryItemDTO::getId)
                .toList());
    }

    @Test
    void discoverReturnsAmapResultsWhenLocalNearbyCandidatesAreEmpty() {
        GeoProperties geoProperties = new GeoProperties();
        geoProperties.setShopPageSize(10);
        geoProperties.setMaxShopPageSize(20);
        geoProperties.setNearbyRadiusMeters(5000D);
        ShopDiscoveryService service = new ShopDiscoveryService(List.of(
                query -> List.of(),
                query -> query.hasValidPoint() ? List.of(amap("amap-near", "Nearby Amap", "Around Road", 130D)) : List.of()
        ), geoProperties);

        Result result = service.discover(query(1, 10));

        assertTrue(result.getSuccess());
        List<ShopDiscoveryItemDTO> items = items(result);
        assertEquals(1, items.size());
        assertEquals("Nearby Amap", items.get(0).getName());
        assertEquals("amap", items.get(0).getSource());
        assertEquals(1L, result.getTotal());
    }

    @Test
    void discoverDoesNotShowGenericLocalListWhenPointQueryHasNoNearbyCandidates() {
        GeoProperties geoProperties = new GeoProperties();
        geoProperties.setShopPageSize(10);
        geoProperties.setMaxShopPageSize(20);
        geoProperties.setNearbyRadiusMeters(5000D);
        ShopDiscoveryItemDTO fallbackShop = local("9", null, "Fallback Local", "Seed Road", null);
        ShopDiscoveryService service = new ShopDiscoveryService(List.of(
                query -> query.hasValidPoint() ? List.of() : List.of(fallbackShop)
        ), geoProperties);

        Result result = service.discover(query(1, 10));

        assertTrue(result.getSuccess());
        assertTrue(items(result).isEmpty());
        assertEquals(0L, result.getTotal());
    }

    @Test
    void discoverRejectsInvalidCoordinates() {
        ShopDiscoveryService service = discoveryService(List.of(), List.of());

        Result result = service.discover(new ShopDiscoveryQuery().setX(200D).setY(30D));

        assertEquals(false, result.getSuccess());
        assertEquals("SHOP_GEO_INVALID", result.getCode());
    }

    @Test
    void extremePageNumberReturnsEmptyPageWithoutOffsetOverflow() {
        ShopDiscoveryService service = discoveryService(
                List.of(local("1", null, "Local", "Road", 100D)),
                List.of()
        );

        Result result = service.discover(query(Integer.MAX_VALUE, 20));

        assertTrue(result.getSuccess());
        assertTrue(items(result).isEmpty());
    }

    private ShopDiscoveryService discoveryService(List<ShopDiscoveryItemDTO> localItems, List<ShopDiscoveryItemDTO> amapItems) {
        GeoProperties geoProperties = new GeoProperties();
        geoProperties.setShopPageSize(10);
        geoProperties.setMaxShopPageSize(20);
        geoProperties.setNearbyRadiusMeters(5000D);
        return new ShopDiscoveryService(List.of(
                ignored -> localItems,
                ignored -> amapItems
        ), geoProperties);
    }

    private ShopDiscoveryQuery query(int current, int size) {
        return new ShopDiscoveryQuery()
                .setCurrent(current)
                .setSize(size)
                .setX(120.155D)
                .setY(30.274D);
    }

    private ShopDiscoveryItemDTO local(String id, String poiId, String name, String address, Double distance) {
        return new ShopDiscoveryItemDTO()
                .setId(Long.valueOf(id))
                .setSource("local")
                .setSourceId(id)
                .setPoiId(poiId)
                .setName(name)
                .setAddress(address)
                .setDistance(distance)
                .setOrderable(true);
    }

    private ShopDiscoveryItemDTO amap(String sourceId, String name, String address, Double distance) {
        return new ShopDiscoveryItemDTO()
                .setSource("amap")
                .setSourceId(sourceId)
                .setPoiId(sourceId)
                .setName(name)
                .setAddress(address)
                .setDistance(distance)
                .setOrderable(false);
    }

    @SuppressWarnings("unchecked")
    private List<ShopDiscoveryItemDTO> items(Result result) {
        return (List<ShopDiscoveryItemDTO>) result.getData();
    }
}
