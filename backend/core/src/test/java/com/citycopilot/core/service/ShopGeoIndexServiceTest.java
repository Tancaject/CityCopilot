package com.citycopilot.core.service;

import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;

import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_ALL_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_INDEX_META_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_INDEX_READY_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopGeoIndexServiceTest {

    private ShopGeoIndexService service;
    private ShopMapper shopMapper;
    private StringRedisTemplate redisTemplate;
    private GeoOperations<String, String> geoOperations;
    private ValueOperations<String, String> valueOperations;
    private HashOperations<String, Object, Object> hashOperations;

    @BeforeEach
    void setUp() {
        service = new ShopGeoIndexService();
        shopMapper = mock(ShopMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        geoOperations = mock(GeoOperations.class);
        valueOperations = mock(ValueOperations.class);
        hashOperations = mock(HashOperations.class);
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get(SHOP_GEO_INDEX_READY_KEY)).thenReturn("v1:initial");
    }

    @Test
    void rebuildIndexesOnlyVerifiedShopsAndCleansStaleTypeKeys() {
        Shop verified = shop(1L, 10L, 120.10, 30.10, "verified");
        Shop pending = shop(2L, 10L, 120.20, 30.20, "pending");
        Shop failed = shop(3L, 11L, 120.30, 30.30, "failed");
        when(shopMapper.selectList(null)).thenReturn(List.of(verified, pending, failed));
        mockScan(SHOP_GEO_ALL_KEY, SHOP_GEO_KEY + "999",
                SHOP_GEO_INDEX_READY_KEY, SHOP_GEO_INDEX_META_KEY);

        int indexed = service.syncShopGeoIndex();

        assertEquals(1, indexed);
        verify(geoOperations).add(eq(SHOP_GEO_ALL_KEY), any(Point.class), eq("1"));
        verify(geoOperations).add(eq(SHOP_GEO_KEY + "10"), any(Point.class), eq("1"));
        verify(geoOperations, times(2)).add(anyString(), any(Point.class), eq("1"));
        verify(geoOperations, never()).add(anyString(), any(Point.class), eq("2"));
        verify(geoOperations, never()).add(anyString(), any(Point.class), eq("3"));
        verify(redisTemplate).delete(argThat((Collection<String> keys) ->
                keys.contains(SHOP_GEO_ALL_KEY)
                        && keys.contains(SHOP_GEO_KEY + "999")
                        && !keys.contains(SHOP_GEO_INDEX_READY_KEY)
                        && !keys.contains(SHOP_GEO_INDEX_META_KEY)));
        verify(valueOperations).set(eq(SHOP_GEO_INDEX_READY_KEY),
                argThat(generation -> generation != null && generation.startsWith("v1:")));
        verify(hashOperations, atLeastOnce()).putAll(eq(SHOP_GEO_INDEX_META_KEY),
                argThat(metadata -> "ready".equals(metadata.get("status"))
                        && "1".equals(metadata.get("indexed"))
                        && String.valueOf(metadata.get("generation")).startsWith("v1:")));
    }

    @Test
    void rebuildFailureNeverMarksIndexReady() {
        Shop verified = shop(1L, 10L, 120.10, 30.10, "verified");
        when(shopMapper.selectList(null)).thenReturn(List.of(verified));
        mockScan();
        when(geoOperations.add(anyString(), any(Point.class), anyString()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(IllegalStateException.class, service::syncShopGeoIndex);

        verify(valueOperations, never()).set(eq(SHOP_GEO_INDEX_READY_KEY), anyString());
        verify(redisTemplate, atLeastOnce()).delete(SHOP_GEO_INDEX_READY_KEY);
        verify(hashOperations, atLeastOnce()).putAll(eq(SHOP_GEO_INDEX_META_KEY),
                argThat(metadata -> "failed".equals(metadata.get("status"))
                        && "IllegalStateException".equals(metadata.get("error"))));
    }

    @Test
    void readyRequiresGenerationToken() {
        when(valueOperations.get(SHOP_GEO_INDEX_READY_KEY))
                .thenReturn("v1:test-generation", "1", "true", null);

        assertTrue(service.isReady());
        assertFalse(service.isReady());
        assertFalse(service.isReady());
        assertFalse(service.isReady());
    }

    @Test
    void refreshAddsVerifiedShop() {
        Shop verified = shop(1L, 10L, 120.10, 30.10, "verified");

        service.refreshShopGeoIndex(null, verified);

        verify(geoOperations).remove(SHOP_GEO_ALL_KEY, "1");
        verify(geoOperations).remove(SHOP_GEO_KEY + "10", "1");
        verify(geoOperations).add(eq(SHOP_GEO_ALL_KEY), any(Point.class), eq("1"));
        verify(geoOperations).add(eq(SHOP_GEO_KEY + "10"), any(Point.class), eq("1"));
        verify(redisTemplate).delete(SHOP_GEO_INDEX_READY_KEY);
        verify(valueOperations).set(eq(SHOP_GEO_INDEX_READY_KEY),
                argThat(generation -> generation != null
                        && generation.startsWith("v1:")
                        && !"v1:initial".equals(generation)));
    }

    @Test
    void refreshRemovesPendingAndFailedShopsWithoutAddingThem() {
        Shop before = shop(1L, 10L, 120.10, 30.10, "verified");

        service.refreshShopGeoIndex(before, shop(1L, 10L, 120.10, 30.10, "pending"));
        verify(geoOperations).remove(SHOP_GEO_ALL_KEY, "1");
        verify(geoOperations).remove(SHOP_GEO_KEY + "10", "1");
        verify(geoOperations, never()).add(anyString(), any(Point.class), anyString());

        reset(geoOperations);

        service.refreshShopGeoIndex(before, shop(1L, 10L, 120.10, 30.10, "failed"));
        verify(geoOperations).remove(SHOP_GEO_ALL_KEY, "1");
        verify(geoOperations).remove(SHOP_GEO_KEY + "10", "1");
        verify(geoOperations, never()).add(anyString(), any(Point.class), anyString());
    }

    @Test
    void refreshMovesVerifiedShopWhenCoordinateAndCategoryChange() {
        Shop before = shop(1L, 10L, 120.10, 30.10, "verified");
        Shop after = shop(1L, 11L, 121.20, 31.20, "verified");

        service.refreshShopGeoIndex(before, after);

        verify(geoOperations).remove(SHOP_GEO_ALL_KEY, "1");
        verify(geoOperations).remove(SHOP_GEO_KEY + "10", "1");
        verify(geoOperations).remove(SHOP_GEO_KEY + "11", "1");
        verify(geoOperations).add(eq(SHOP_GEO_ALL_KEY),
                argThat(point -> point.getX() == 121.20 && point.getY() == 31.20), eq("1"));
        verify(geoOperations).add(eq(SHOP_GEO_KEY + "11"),
                argThat(point -> point.getX() == 121.20 && point.getY() == 31.20), eq("1"));
    }

    @Test
    void incrementalFailureClearsReadyMarkerAndMarksIndexStale() {
        Shop after = shop(1L, 10L, 120.10, 30.10, "verified");
        when(geoOperations.remove(SHOP_GEO_ALL_KEY, "1"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(IllegalStateException.class,
                () -> service.refreshShopGeoIndex(null, after));

        verify(redisTemplate, atLeastOnce()).delete(SHOP_GEO_INDEX_READY_KEY);
        verify(hashOperations).putAll(eq(SHOP_GEO_INDEX_META_KEY),
                argThat(metadata -> "stale".equals(metadata.get("status"))
                        && "IllegalStateException".equals(metadata.get("error"))));
        verify(valueOperations, never()).set(eq(SHOP_GEO_INDEX_READY_KEY), anyString());
    }

    @Test
    void incrementalFailureDoesNotTrustSurvivingRemoteGeneration() {
        Shop after = shop(1L, 10L, 120.10, 30.10, "verified");
        when(geoOperations.remove(SHOP_GEO_ALL_KEY, "1"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(redisTemplate.delete(SHOP_GEO_INDEX_READY_KEY))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(valueOperations.get(SHOP_GEO_INDEX_READY_KEY)).thenReturn("v1:old-generation");

        assertThrows(IllegalStateException.class,
                () -> service.refreshShopGeoIndex(null, after));

        assertFalse(service.isReady());
        verify(valueOperations, times(1)).get(SHOP_GEO_INDEX_READY_KEY);
    }

    private void mockScan(String... keys) {
        Cursor<String> cursor = mock(Cursor.class);
        int[] position = {0};
        when(cursor.hasNext()).thenAnswer(invocation -> position[0] < keys.length);
        when(cursor.next()).thenAnswer(invocation -> keys[position[0]++]);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
    }

    private Shop shop(Long id, Long typeId, Double x, Double y, String status) {
        return new Shop()
                .setId(id)
                .setTypeId(typeId)
                .setX(x)
                .setY(y)
                .setGeoStatus(status);
    }
}
