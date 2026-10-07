package com.citycopilot.core.utils;

import cn.hutool.json.JSONUtil;
import com.citycopilot.core.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.citycopilot.core.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.citycopilot.core.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.citycopilot.core.utils.RedisConstants.CACHE_SHOP_TTL_JITTER;
import static com.citycopilot.core.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.citycopilot.core.utils.RedisConstants.LOCK_SHOP_TTL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheClientTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private CacheClient cacheClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(eq(CacheClient.WRITE_IF_OWNER_SCRIPT), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        cacheClient = new CacheClient(redis, 200L, 1L);
    }

    @Test
    void cacheHitNeverLoadsDatabaseOrAcquiresLock() {
        Shop cached = new Shop().setId(7L).setName("cached");
        when(values.get(CACHE_SHOP_KEY + 7L)).thenReturn(JSONUtil.toJsonStr(cached));
        @SuppressWarnings("unchecked")
        Function<Long, Shop> fallback = mock(Function.class);

        Shop result = query(7L, fallback);

        assertThat(result.getName()).isEqualTo("cached");
        verify(fallback, never()).apply(any());
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Long.class), any(TimeUnit.class));
    }

    @Test
    void negativeCacheNeverLoadsDatabase() {
        when(values.get(CACHE_SHOP_KEY + 8L)).thenReturn("");
        @SuppressWarnings("unchecked")
        Function<Long, Shop> fallback = mock(Function.class);

        assertThat(query(8L, fallback)).isNull();

        verify(fallback, never()).apply(any());
    }

    @Test
    void coldMissLoadsOnceAndCachesWithJitteredTtl() {
        String key = CACHE_SHOP_KEY + 9L;
        String lockKey = LOCK_SHOP_KEY + 9L;
        Shop loaded = new Shop().setId(9L).setName("database");
        when(values.get(key)).thenReturn(null);
        when(values.setIfAbsent(eq(lockKey), anyString(), eq(LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(redis.execute(eq(CacheClient.UNLOCK_IF_OWNER_SCRIPT), eq(List.of(lockKey)), anyString()))
                .thenReturn(1L);
        AtomicInteger databaseLoads = new AtomicInteger();

        Shop result = query(9L, ignored -> {
            databaseLoads.incrementAndGet();
            return loaded;
        });

        assertThat(result).isSameAs(loaded);
        assertThat(databaseLoads).hasValue(1);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(
                eq(lockKey),
                token.capture(),
                eq(LOCK_SHOP_TTL),
                eq(TimeUnit.SECONDS)
        );
        verify(redis).execute(eq(CacheClient.WRITE_IF_OWNER_SCRIPT), eq(List.of(lockKey, key)),
                eq(token.getValue()), anyString(), anyString());
        verify(redis).execute(
                eq(CacheClient.UNLOCK_IF_OWNER_SCRIPT),
                eq(List.of(lockKey)),
                eq(token.getValue())
        );
    }

    @Test
    void missingShopUsesShortJitteredNegativeCache() {
        String key = CACHE_SHOP_KEY + 10L;
        String lockKey = LOCK_SHOP_KEY + 10L;
        when(values.get(key)).thenReturn(null);
        when(values.setIfAbsent(eq(lockKey), anyString(), eq(LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(redis.execute(eq(CacheClient.UNLOCK_IF_OWNER_SCRIPT), eq(List.of(lockKey)), anyString()))
                .thenReturn(1L);

        assertThat(query(10L, ignored -> null)).isNull();

        verify(redis).execute(eq(CacheClient.WRITE_IF_OWNER_SCRIPT), eq(List.of(lockKey, key)),
                anyString(), eq(""), anyString());
    }

    @Test
    void failedDatabaseLoadDoesNotCacheAndReleasesLockForRetry() {
        String key = CACHE_SHOP_KEY + 14L;
        String lockKey = LOCK_SHOP_KEY + 14L;
        Shop recovered = new Shop().setId(14L).setName("recovered");
        when(values.get(key)).thenReturn(null);
        when(values.setIfAbsent(eq(lockKey), anyString(), eq(LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(true, true);
        when(redis.execute(eq(CacheClient.UNLOCK_IF_OWNER_SCRIPT), eq(List.of(lockKey)), anyString()))
                .thenReturn(1L);

        assertThatThrownBy(() -> query(14L, ignored -> {
            throw new IllegalStateException("database unavailable");
        })).isInstanceOf(IllegalStateException.class);

        verify(redis, never()).execute(eq(CacheClient.WRITE_IF_OWNER_SCRIPT), anyList(), anyString(), anyString(), anyString());

        Shop result = query(14L, ignored -> recovered);

        assertThat(result).isSameAs(recovered);
        verify(redis).execute(eq(CacheClient.WRITE_IF_OWNER_SCRIPT), eq(List.of(lockKey, key)),
                anyString(), anyString(), anyString());
        verify(redis, org.mockito.Mockito.times(2)).execute(
                eq(CacheClient.UNLOCK_IF_OWNER_SCRIPT),
                eq(List.of(lockKey)),
                anyString()
        );
    }

    @Test
    void lockContenderReadsWinnerCacheWithoutDatabaseFallback() {
        String key = CACHE_SHOP_KEY + 11L;
        String lockKey = LOCK_SHOP_KEY + 11L;
        Shop winner = new Shop().setId(11L).setName("winner");
        when(values.get(key)).thenReturn(null, JSONUtil.toJsonStr(winner));
        when(values.setIfAbsent(eq(lockKey), anyString(), eq(LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(false);
        @SuppressWarnings("unchecked")
        Function<Long, Shop> fallback = mock(Function.class);

        Shop result = query(11L, fallback);

        assertThat(result.getName()).isEqualTo("winner");
        verify(fallback, never()).apply(any());
        verify(redis, never()).execute(eq(CacheClient.UNLOCK_IF_OWNER_SCRIPT), any(), anyString());
    }

    @Test
    void lockContentionIsBoundedAndDoesNotFallThroughToDatabase() {
        cacheClient = new CacheClient(redis, 1L, 1L);
        String key = CACHE_SHOP_KEY + 12L;
        String lockKey = LOCK_SHOP_KEY + 12L;
        when(values.get(key)).thenReturn(null);
        when(values.setIfAbsent(eq(lockKey), anyString(), eq(LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenReturn(false);
        @SuppressWarnings("unchecked")
        Function<Long, Shop> fallback = mock(Function.class);

        assertThatThrownBy(() -> query(12L, fallback))
                .isInstanceOf(CacheRebuildBusyException.class);

        verify(fallback, never()).apply(any());
    }

    @Test
    void jitterAlwaysStaysInsideConfiguredWindow() {
        Duration base = Duration.ofMinutes(CACHE_SHOP_TTL);
        Duration maximum = base.plusMinutes(CACHE_SHOP_TTL_JITTER);
        Duration nullBase = Duration.ofMinutes(RedisConstants.CACHE_NULL_TTL);
        Duration nullMaximum = nullBase.plusMinutes(RedisConstants.CACHE_NULL_TTL_JITTER);

        for (int i = 0; i < 200; i++) {
            Duration ttl = cacheClient.jitteredTtl(
                    CACHE_SHOP_TTL,
                    CACHE_SHOP_TTL_JITTER,
                    TimeUnit.MINUTES
            );
            assertThat(ttl).isBetween(base, maximum);
            Duration nullTtl = cacheClient.jitteredTtl(
                    RedisConstants.CACHE_NULL_TTL,
                    RedisConstants.CACHE_NULL_TTL_JITTER,
                    TimeUnit.MINUTES
            );
            assertThat(nullTtl).isBetween(nullBase, nullMaximum);
        }
    }

    @Test
    void concurrentColdMissesUseOneDatabaseLoader() throws Exception {
        String key = CACHE_SHOP_KEY + 13L;
        String lockKey = LOCK_SHOP_KEY + 13L;
        AtomicReference<String> cachedJson = new AtomicReference<>();
        AtomicReference<String> lockOwner = new AtomicReference<>();
        AtomicInteger databaseLoads = new AtomicInteger();
        Shop loaded = new Shop().setId(13L).setName("single-flight");
        cacheClient = new CacheClient(redis, 1_000L, 2L);

        when(values.get(key)).thenAnswer(ignored -> cachedJson.get());
        when(values.setIfAbsent(eq(lockKey), anyString(), eq(LOCK_SHOP_TTL), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> lockOwner.compareAndSet(null, invocation.getArgument(1, String.class)));
        when(redis.execute(eq(CacheClient.WRITE_IF_OWNER_SCRIPT), eq(List.of(lockKey, key)),
                anyString(), anyString(), anyString())).thenAnswer(invocation -> {
                    if (!invocation.getArgument(2, String.class).equals(lockOwner.get())) return 0L;
                    cachedJson.set(invocation.getArgument(3, String.class));
                    return 1L;
                });
        when(redis.execute(eq(CacheClient.UNLOCK_IF_OWNER_SCRIPT), eq(List.of(lockKey)), anyString()))
                .thenAnswer(invocation -> {
                    String token = invocation.getArgument(2, String.class);
                    return lockOwner.compareAndSet(token, null) ? 1L : 0L;
                });

        int callers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Shop>> futures = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return query(13L, ignored -> {
                            databaseLoads.incrementAndGet();
                            try {
                                Thread.sleep(50L);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("test loader interrupted", error);
                            }
                            return loaded;
                        });
                    }))
                    .toList();
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<Shop> future : futures) {
                assertThat(future.get(2, TimeUnit.SECONDS).getId()).isEqualTo(13L);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(databaseLoads).hasValue(1);
        assertThat(lockOwner.get()).isNull();
    }

    private Shop query(Long id, Function<Long, Shop> fallback) {
        return cacheClient.queryWithMutex(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                fallback,
                CACHE_SHOP_TTL,
                CACHE_SHOP_TTL_JITTER,
                TimeUnit.MINUTES
        );
    }
}
