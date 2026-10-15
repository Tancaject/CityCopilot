package com.citycopilot.core.utils;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.projection.ShopProjectionLeaseService;
import com.citycopilot.core.projection.ShopProjectionOutboxProjector;
import com.citycopilot.core.projection.ShopProjectionOutboxRepository;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

import static com.citycopilot.core.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses a UUID MySQL database and an explicitly supplied disposable Redis DB; never flushes Redis. */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_MIGRATION_TEST_URL", matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_SECKILL_TEST_REDIS_DATABASE", matches = "[2-9]|[1-9][0-9]+")
class CoreCacheAndRateLimitIT {
    private static final String SERVER = System.getenv("CITYCOPILOT_MIGRATION_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_PASSWORD", "");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL&forceConnectionTimeZoneToSession=true";
    private static final String DATABASE = "cc_cache_" + UUID.randomUUID().toString().replace("-", "");
    private static HikariDataSource pool;
    private static JdbcTemplate sql;
    private static ShopMapper shops;
    private static TransactionTemplate transactions;
    private static ShopProjectionOutboxRepository outbox;
    private static ShopProjectionOutboxProjector projector;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static CacheClient first;
    private static CacheClient second;
    private static RedisMessageListenerContainer listeners;

    @BeforeAll
    static void setup() throws Exception {
        try (var admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(SERVER + DATABASE + OPTIONS);
        config.setUsername(USER);
        config.setPassword(PASSWORD);
        pool = new HikariDataSource(config);
        sql = new JdbcTemplate(pool);
        Flyway.configure().dataSource(pool).locations("filesystem:" + Path.of("../../deploy/mysql/core").toAbsolutePath().normalize())
                .cleanDisabled(true).load().migrate();
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(ShopMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(pool);
        factory.setConfiguration(mybatis);
        shops = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(ShopMapper.class);
        var manager = new DataSourceTransactionManager(pool);
        transactions = new TransactionTemplate(manager);
        outbox = new ShopProjectionOutboxRepository(sql, JsonMapper.builder().findAndAddModules().build());
        var redisConfig = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("CITYCOPILOT_SECKILL_TEST_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("CITYCOPILOT_SECKILL_TEST_REDIS_PORT", "6380")));
        redisConfig.setDatabase(Integer.parseInt(System.getenv("CITYCOPILOT_SECKILL_TEST_REDIS_DATABASE")));
        redisConfig.setPassword(System.getenv().getOrDefault("CITYCOPILOT_SECKILL_TEST_REDIS_PASSWORD", ""));
        redisFactory = new LettuceConnectionFactory(redisConfig);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        first = new CacheClient(redis);
        second = new CacheClient(redis);
        listeners = new RedisMessageListenerContainer();
        listeners.setConnectionFactory(redisFactory);
        listeners.addMessageListener(first, new ChannelTopic(CacheClient.INVALIDATION_CHANNEL));
        listeners.addMessageListener(second, new ChannelTopic(CacheClient.INVALIDATION_CHANNEL));
        listeners.afterPropertiesSet();
        listeners.start();
        ShopGeoIndexService geo = new ShopGeoIndexService();
        ReflectionTestUtils.setField(geo, "shopMapper", shops);
        ReflectionTestUtils.setField(geo, "stringRedisTemplate", redis);
        projector = new ShopProjectionOutboxProjector(outbox, new ShopProjectionLeaseService(sql, manager),
                shops, geo, new ShopProjectionProperties(), first);
    }

    @AfterAll
    static void cleanup() throws Exception {
        UserHolder.removeUser();
        if (listeners != null) listeners.destroy();
        if (redisFactory != null) redisFactory.destroy();
        if (pool != null) pool.close();
        try (var admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void outboxInvalidatesBothLocalCachesOnlyAfterCommittedChange() throws Exception {
        long id = newShop();
        String oldGeneration = redis.opsForValue().get(SHOP_GEO_INDEX_READY_KEY);
        String generation = "v2:cache-it:" + UUID.randomUUID();
        try {
            assertEquals("before", read(first, id).getName());
            assertEquals("before", read(second, id).getName());
            transactions.executeWithoutResult(status -> {
                Shop before = shops.selectById(id);
                sql.update("UPDATE shop SET name='after' WHERE id=?", id);
                outbox.append(before, shops.selectById(id));
            });
            assertEquals("before", read(second, id).getName());
            redis.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, generation);
            projector.projectReadyEvents();
            assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM shop_projection_outbox WHERE shop_id=? AND status='PROCESSED'", Integer.class, id));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((!"after".equals(read(first, id).getName()) || !"after".equals(read(second, id).getName()))
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals("after", read(first, id).getName());
            assertEquals("after", read(second, id).getName());
        } finally {
            if (oldGeneration == null) redis.delete(SHOP_GEO_INDEX_READY_KEY);
            else redis.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, oldGeneration);
            redis.delete(List.of(CACHE_SHOP_KEY + id, LOCK_SHOP_KEY + id,
                    SHOP_GEO_KEY + "{projection}:generation:" + generation + ":versions",
                    SHOP_GEO_KEY + "{projection}:generation:" + generation + ":types"));
        }
    }

    @Test
    void invalidationReturnsAnOldDatabaseLoadWithoutRefillingEitherCache() throws Exception {
        long id = newShop();
        // 没有广播订阅，确保断言不会被稍后到达的本地失效消息掩盖。
        CacheClient rebuilding = new CacheClient(redis);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Shop> stale = executor.submit(() -> rebuilding.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, ignored -> {
                Shop old = shops.selectById(id);
                loaded.countDown();
                try { assertTrue(resume.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return old;
            }, CACHE_SHOP_TTL, CACHE_SHOP_TTL_JITTER, TimeUnit.MINUTES));
            assertTrue(loaded.await(3, TimeUnit.SECONDS));
            sql.update("UPDATE shop SET name='after' WHERE id=?", id);
            second.invalidateShop(id);
            resume.countDown();
            assertEquals("before", stale.get(3, TimeUnit.SECONDS).getName());
            assertNull(redis.opsForValue().get(CACHE_SHOP_KEY + id));
            assertEquals("after", read(rebuilding, id).getName());
            assertEquals("after", read(second, id).getName());
        } finally {
            resume.countDown();
            executor.shutdownNow();
            redis.delete(List.of(CACHE_SHOP_KEY + id, LOCK_SHOP_KEY + id));
        }
    }

    @Test
    void tokenBucketIsSharedAcrossInstancesAndFailsClosedOnRedisErrors() throws Exception {
        long userId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String key = "limit:user:seckill:" + userId;
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
        var firstLimit = new UserRateLimitInterceptor(redis);
        var secondLimit = new UserRateLimitInterceptor(redis);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/1");
        try {
            // Future timestamp prevents wall-clock scheduling from refilling the bucket during this assertion.
            redis.opsForHash().putAll(key, java.util.Map.of("tokens", "5", "updated", "9999999999999"));
            for (int i = 0; i < 5; i++) assertTrue((i % 2 == 0 ? firstLimit : secondLimit)
                    .preHandle(request, new MockHttpServletResponse(), this));
            var rejected = new MockHttpServletResponse();
            assertFalse(secondLimit.preHandle(request, rejected, this));
            assertEquals(429, rejected.getStatus());
            assertEquals("1", rejected.getHeader("Retry-After"));
            redis.delete(key);
            assertTrue(firstLimit.preHandle(request, new MockHttpServletResponse(), this));
            redis.delete(key);
            redis.opsForValue().set(key, "invalid-bucket-type");
            var unavailable = new MockHttpServletResponse();
            assertFalse(firstLimit.preHandle(request, unavailable, this));
            assertEquals(503, unavailable.getStatus());
        } finally {
            redis.delete(key);
            UserHolder.removeUser();
        }
    }

    private static long newShop() {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1);
        sql.update("INSERT INTO shop(id,name,type_id,images,area,address,sold,comments,score) VALUES (?,'before',1,'','','',0,0,0)", id);
        return id;
    }

    private static Shop read(CacheClient cache, long id) {
        return cache.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, shops::selectById,
                CACHE_SHOP_TTL, CACHE_SHOP_TTL_JITTER, TimeUnit.MINUTES);
    }
}
