package com.citycopilot.core.service.discovery;

import com.citycopilot.core.service.ShopGeoIndexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisShopGeoSearchServiceTest {

    @Test
    void reportsNotReadyWithoutQueryingGeoData() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ShopGeoIndexService indexService = mock(ShopGeoIndexService.class);
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geo);
        when(indexService.readyGeneration()).thenReturn(null);

        RedisShopGeoSearchService.SearchResult result =
                new RedisShopGeoSearchService(redisTemplate, indexService)
                        .search(null, 120D, 30D, 5000D, 10);

        assertFalse(result.ready());
        assertTrue(result.candidates().isEmpty());
        verify(geo, never()).search(
                any(String.class),
                any(GeoReference.class),
                any(Distance.class),
                any(RedisGeoCommands.GeoSearchCommandArgs.class)
        );
    }

    @Test
    void queriesInKilometersAndReturnsMeters() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ShopGeoIndexService indexService = mock(ShopGeoIndexService.class);
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geo);
        when(indexService.readyGeneration()).thenReturn("v1:test-generation");

        RedisGeoCommands.GeoLocation<String> location =
                new RedisGeoCommands.GeoLocation<>("42", new Point(120D, 30D));
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = new GeoResults<>(
                List.of(new GeoResult<>(location, new Distance(1.25D, Metrics.KILOMETERS))),
                Metrics.KILOMETERS
        );
        when(geo.search(
                eq("shop:geo:7"),
                any(GeoReference.class),
                any(Distance.class),
                any(RedisGeoCommands.GeoSearchCommandArgs.class)
        )).thenReturn(geoResults);

        RedisShopGeoSearchService.SearchResult result =
                new RedisShopGeoSearchService(redisTemplate, indexService)
                        .search(7, 120D, 30D, 5000D, 10);

        assertTrue(result.ready());
        assertEquals(List.of(new RedisShopGeoSearchService.Candidate(42L, 1250D)), result.candidates());
        ArgumentCaptor<Distance> radius = ArgumentCaptor.forClass(Distance.class);
        verify(geo).search(
                eq("shop:geo:7"),
                any(GeoReference.class),
                radius.capture(),
                any(RedisGeoCommands.GeoSearchCommandArgs.class)
        );
        assertEquals(Metrics.KILOMETERS, radius.getValue().getMetric());
        assertEquals(5D, radius.getValue().getValue(), 0.0001D);
    }

    @Test
    void reportsNotReadyWhenGenerationChangesDuringSearch() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ShopGeoIndexService indexService = mock(ShopGeoIndexService.class);
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geo = mock(GeoOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geo);
        when(indexService.readyGeneration())
                .thenReturn("v1:before")
                .thenReturn("v1:after");
        when(geo.search(
                eq("shop:geo:all"),
                any(GeoReference.class),
                any(Distance.class),
                any(RedisGeoCommands.GeoSearchCommandArgs.class)
        )).thenReturn(new GeoResults<>(List.of(), Metrics.KILOMETERS));

        RedisShopGeoSearchService.SearchResult result =
                new RedisShopGeoSearchService(redisTemplate, indexService)
                        .search(null, 120D, 30D, 5000D, 10);

        assertFalse(result.ready());
        assertTrue(result.candidates().isEmpty());
    }
}
