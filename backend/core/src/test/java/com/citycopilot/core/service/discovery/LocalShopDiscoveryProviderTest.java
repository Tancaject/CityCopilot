package com.citycopilot.core.service.discovery;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.service.IShopService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalShopDiscoveryProviderTest {

    @Test
    void redisModeHydratesCandidatesThroughAuthoritativeMysqlFilters() {
        IShopService shopService = mock(IShopService.class);
        RedisShopGeoSearchService redis = mock(RedisShopGeoSearchService.class);
        GeoProperties properties = properties(GeoProperties.LocalDiscoveryMode.REDIS);
        when(redis.search(anyInt(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(RedisShopGeoSearchService.SearchResult.ready(List.of(
                        new RedisShopGeoSearchService.Candidate(1L, 120D),
                        new RedisShopGeoSearchService.Candidate(2L, 240D)
                )));
        when(shopService.list(any(Wrapper.class))).thenReturn(List.of(shop(1L, 7L, "Coffee One", 120.001D, 30D)));

        List<ShopDiscoveryItemDTO> items = provider(shopService, properties, redis)
                .discover(query().setTypeId(7).setKeyword("Coffee"));

        assertEquals(1, items.size());
        assertEquals(1L, items.get(0).getId());
        assertTrue(items.get(0).getDistance() > 90D);
        assertTrue(items.get(0).getDistance() < 110D);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Shop>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(shopService).list(wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertTrue(sql.contains("geo_status"));
        assertTrue(sql.contains("type_id"));
        assertTrue(sql.contains("name"));
        assertTrue(sql.contains("id IN"));
    }

    @Test
    void missingRedisIndexFallsBackToBoundingBoxAndHaversineSqlPath() {
        IShopService shopService = mock(IShopService.class);
        RedisShopGeoSearchService redis = mock(RedisShopGeoSearchService.class);
        GeoProperties properties = properties(GeoProperties.LocalDiscoveryMode.REDIS);
        when(redis.search(isNull(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(RedisShopGeoSearchService.SearchResult.notReady());
        when(shopService.list(any(Wrapper.class))).thenReturn(List.of(
                shop(1L, 7L, "Near", 120.001D, 30D),
                shop(2L, 7L, "Outside", 121D, 30D)
        ));

        List<ShopDiscoveryItemDTO> items = provider(shopService, properties, redis).discover(query());

        assertEquals(1, items.size());
        assertEquals(1L, items.get(0).getId());
        assertTrue(items.get(0).getDistance() < 5000D);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Shop>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(shopService).list(wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertTrue(sql.contains("y BETWEEN"));
        assertTrue(sql.contains("x BETWEEN"));
        assertFalse(sql.toUpperCase().contains("LIMIT"));
    }

    @Test
    void redisFailureFallsBackToSql() {
        IShopService shopService = mock(IShopService.class);
        RedisShopGeoSearchService redis = mock(RedisShopGeoSearchService.class);
        GeoProperties properties = properties(GeoProperties.LocalDiscoveryMode.REDIS);
        when(redis.search(isNull(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(shopService.list(any(Wrapper.class)))
                .thenReturn(List.of(shop(1L, 7L, "Near", 120.001D, 30D)));

        List<ShopDiscoveryItemDTO> items = provider(shopService, properties, redis).discover(query());

        assertEquals(List.of(1L), items.stream().map(ShopDiscoveryItemDTO::getId).toList());
    }

    @Test
    void redisCandidateIsRejectedWhenMysqlCoordinatesMovedOutsideRadius() {
        IShopService shopService = mock(IShopService.class);
        RedisShopGeoSearchService redis = mock(RedisShopGeoSearchService.class);
        GeoProperties properties = properties(GeoProperties.LocalDiscoveryMode.REDIS);
        when(redis.search(isNull(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(RedisShopGeoSearchService.SearchResult.ready(List.of(
                        new RedisShopGeoSearchService.Candidate(1L, 100D)
                )));
        when(shopService.list(any(Wrapper.class)))
                .thenReturn(List.of(shop(1L, 7L, "Moved", 121D, 30D)));

        List<ShopDiscoveryItemDTO> items = provider(shopService, properties, redis).discover(query());

        assertTrue(items.isEmpty());
    }

    @Test
    void mysqlModeDoesNotReadRedis() {
        IShopService shopService = mock(IShopService.class);
        RedisShopGeoSearchService redis = mock(RedisShopGeoSearchService.class);
        GeoProperties properties = properties(GeoProperties.LocalDiscoveryMode.MYSQL);
        when(shopService.list(any(Wrapper.class)))
                .thenReturn(List.of(shop(1L, 7L, "Near", 120.001D, 30D)));

        List<ShopDiscoveryItemDTO> items = provider(shopService, properties, redis).discover(query());

        assertEquals(1, items.size());
        verify(redis, never()).search(isNull(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void shadowModeReturnsSqlResultWhenRedisRankingDiffers() {
        IShopService shopService = mock(IShopService.class);
        RedisShopGeoSearchService redis = mock(RedisShopGeoSearchService.class);
        GeoProperties properties = properties(GeoProperties.LocalDiscoveryMode.SHADOW);
        Shop sqlShop = shop(1L, 7L, "SQL", 120.001D, 30D);
        Shop redisShop = shop(2L, 7L, "Redis", 120.002D, 30D);
        when(shopService.list(any(Wrapper.class)))
                .thenReturn(List.of(sqlShop), List.of(redisShop));
        when(redis.search(isNull(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(RedisShopGeoSearchService.SearchResult.ready(List.of(
                        new RedisShopGeoSearchService.Candidate(2L, 200D)
                )));

        List<ShopDiscoveryItemDTO> items = provider(shopService, properties, redis).discover(query());

        assertEquals(List.of(1L), items.stream().map(ShopDiscoveryItemDTO::getId).toList());
    }

    @Test
    void redisCandidateLimitIsHardCappedWithoutIntegerOverflow() {
        IShopService shopService = mock(IShopService.class);
        RedisShopGeoSearchService redis = mock(RedisShopGeoSearchService.class);
        GeoProperties properties = properties(GeoProperties.LocalDiscoveryMode.REDIS);
        properties.setRedisMaxCandidates(50);
        when(redis.search(isNull(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(RedisShopGeoSearchService.SearchResult.ready(List.of()));

        provider(shopService, properties, redis).discover(query()
                .setCurrent(Integer.MAX_VALUE)
                .setSize(20));

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(redis).search(isNull(), anyDouble(), anyDouble(), anyDouble(), limit.capture());
        assertEquals(50, limit.getValue());
    }

    private LocalShopDiscoveryProvider provider(
            IShopService shopService,
            GeoProperties properties,
            RedisShopGeoSearchService redis
    ) {
        return new LocalShopDiscoveryProvider(shopService, properties, redis);
    }

    private GeoProperties properties(GeoProperties.LocalDiscoveryMode mode) {
        GeoProperties properties = new GeoProperties();
        properties.setLocalDiscoveryMode(mode);
        properties.setNearbyRadiusMeters(5000D);
        properties.setShopPageSize(10);
        properties.setMaxShopPageSize(20);
        return properties;
    }

    private ShopDiscoveryQuery query() {
        return new ShopDiscoveryQuery()
                .setCurrent(1)
                .setSize(10)
                .setX(120D)
                .setY(30D);
    }

    private Shop shop(Long id, Long typeId, String name, double x, double y) {
        return new Shop()
                .setId(id)
                .setTypeId(typeId)
                .setName(name)
                .setGeoStatus("verified")
                .setX(x)
                .setY(y);
    }
}
