package com.citycopilot.core.service.discovery;

import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.service.ShopGeoIndexService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_ALL_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_INDEX_READY_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class RedisShopGeoSearchServiceIT {

    private static final int REDIS_PORT = 6379;
    private static final Point SEARCH_ORIGIN = new Point(120D, 30D);

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisShopGeoSearchService searchService;
    private ShopGeoIndexService indexService;
    private ShopMapper shopMapper;

    @BeforeAll
    static void connectToRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(),
                REDIS.getMappedPort(REDIS_PORT)
        );
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        shopMapper = mock(ShopMapper.class);
        indexService = indexService(shopMapper);
        searchService = new RedisShopGeoSearchService(redisTemplate, indexService);
    }

    @Test
    void readyGateBlocksExistingGeoDataUntilExactMarkerIsPresent() {
        redisTemplate.opsForGeo().add(SHOP_GEO_ALL_KEY, SEARCH_ORIGIN, "1");

        RedisShopGeoSearchService.SearchResult missingMarker =
                searchService.search(null, SEARCH_ORIGIN.getX(), SEARCH_ORIGIN.getY(), 1000D, 10);

        assertFalse(missingMarker.ready());
        assertTrue(missingMarker.candidates().isEmpty());

        redisTemplate.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, "true");
        RedisShopGeoSearchService.SearchResult wrongMarker =
                searchService.search(null, SEARCH_ORIGIN.getX(), SEARCH_ORIGIN.getY(), 1000D, 10);

        assertFalse(wrongMarker.ready());

        redisTemplate.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, "1");
        RedisShopGeoSearchService.SearchResult legacyBooleanMarker =
                searchService.search(null, SEARCH_ORIGIN.getX(), SEARCH_ORIGIN.getY(), 1000D, 10);

        assertFalse(legacyBooleanMarker.ready());

        redisTemplate.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, "v1:manual-generation");
        RedisShopGeoSearchService.SearchResult ready =
                searchService.search(null, SEARCH_ORIGIN.getX(), SEARCH_ORIGIN.getY(), 1000D, 10);

        assertTrue(ready.ready());
        assertEquals(List.of(1L), ready.candidates().stream()
                .map(RedisShopGeoSearchService.Candidate::shopId)
                .toList());
    }

    @Test
    void rebuildGeoAddsSearchableCategoryMembersInAscendingMeterDistances() {
        when(shopMapper.selectList(null)).thenReturn(List.of(
                shop(99L, 7L, 120D, 30D, "verified"),
                shop(1L, 7L, 120D, 30.001D, "verified"),
                shop(2L, 7L, 120D, 30.005D, "verified"),
                shop(3L, 7L, 120D, 30.020D, "verified"),
                shop(4L, 8L, 120D, 30.0002D, "verified"),
                shop(5L, 7L, 120D, 30.0001D, "pending")
        ));
        assertEquals(5, indexService.syncShopGeoIndex());
        assertTrue(indexService.isReady());

        RedisShopGeoSearchService.SearchResult categorySeven =
                searchService.search(7, SEARCH_ORIGIN.getX(), SEARCH_ORIGIN.getY(), 1000D, 10);

        assertTrue(categorySeven.ready());
        assertEquals(List.of(99L, 1L, 2L), categorySeven.candidates().stream()
                .map(RedisShopGeoSearchService.Candidate::shopId)
                .toList());
        assertTrue(categorySeven.candidates().get(0).distanceMeters()
                <= categorySeven.candidates().get(1).distanceMeters());
        assertTrue(categorySeven.candidates().get(1).distanceMeters()
                < categorySeven.candidates().get(2).distanceMeters());

        Distance rawKilometers = redisTemplate.opsForGeo()
                .distance(SHOP_GEO_KEY + "7", "99", "2", Metrics.KILOMETERS);
        assertNotNull(rawKilometers);
        assertEquals(
                rawKilometers.getValue() * 1000D,
                categorySeven.candidates().get(2).distanceMeters(),
                1D
        );
        assertTrue(categorySeven.candidates().get(2).distanceMeters() > 500D);
        assertTrue(categorySeven.candidates().get(2).distanceMeters() < 600D);

        RedisShopGeoSearchService.SearchResult emptyCategory =
                searchService.search(404, SEARCH_ORIGIN.getX(), SEARCH_ORIGIN.getY(), 1000D, 10);

        assertTrue(emptyCategory.ready());
        assertTrue(emptyCategory.candidates().isEmpty());
    }

    @Test
    void emptyRebuildStillPublishesAReadyEmptyIndex() {
        when(shopMapper.selectList(null)).thenReturn(List.of());

        assertEquals(0, indexService.syncShopGeoIndex());
        assertTrue(indexService.isReady());

        RedisShopGeoSearchService.SearchResult result =
                searchService.search(7, SEARCH_ORIGIN.getX(), SEARCH_ORIGIN.getY(), 1000D, 10);

        assertTrue(result.ready());
        assertTrue(result.candidates().isEmpty());
    }

    private ShopGeoIndexService indexService(ShopMapper shopMapper) {
        ShopGeoIndexService service = new ShopGeoIndexService();
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        return service;
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
