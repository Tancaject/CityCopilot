package com.citycopilot.core.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.citycopilot.core.utils.RedisConstants.CACHE_NULL_TTL;
import static com.citycopilot.core.utils.RedisConstants.CACHE_NULL_TTL_JITTER;
import static com.citycopilot.core.utils.RedisConstants.CACHE_REBUILD_RETRY_MILLIS;
import static com.citycopilot.core.utils.RedisConstants.CACHE_REBUILD_WAIT_MILLIS;
import static com.citycopilot.core.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.citycopilot.core.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {

    static final DefaultRedisScript<Long> UNLOCK_IF_OWNER_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final long rebuildWaitMillis;
    private final long rebuildRetryMillis;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, CACHE_REBUILD_WAIT_MILLIS, CACHE_REBUILD_RETRY_MILLIS);
    }

    CacheClient(
            StringRedisTemplate stringRedisTemplate,
            long rebuildWaitMillis,
            long rebuildRetryMillis
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rebuildWaitMillis = Math.max(1L, rebuildWaitMillis);
        this.rebuildRetryMillis = Math.max(1L, rebuildRetryMillis);
    }

    public void set(
            String key,
            Object value,
            long ttl,
            long ttlJitter,
            TimeUnit unit
    ) {
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(value),
                jitteredTtl(ttl, ttlJitter, unit)
        );
    }

    /**
     * Cache-aside with a distributed mutex. The lock holder rechecks Redis
     * before loading MySQL; contenders wait briefly for that cached result
     * instead of querying MySQL themselves.
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            long ttl,
            long ttlJitter,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;
        CacheLookup<R> cached = read(key, type);
        if (cached.resolved()) {
            return cached.value();
        }

        String lockKey = LOCK_SHOP_KEY + id;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(rebuildWaitMillis);
        while (true) {
            String lockToken = tryLock(lockKey);
            if (lockToken != null) {
                try {
                    // Another request may have populated the cache between
                    // this request's first read and lock acquisition.
                    cached = read(key, type);
                    if (cached.resolved()) {
                        return cached.value();
                    }

                    R loaded = dbFallback.apply(id);
                    if (loaded == null) {
                        setNull(key);
                        return null;
                    }
                    set(key, loaded, ttl, ttlJitter, unit);
                    return loaded;
                } finally {
                    unlockIfOwner(lockKey, lockToken);
                }
            }

            cached = read(key, type);
            if (cached.resolved()) {
                return cached.value();
            }
            if (System.nanoTime() >= deadline) {
                throw new CacheRebuildBusyException("店铺缓存正在重建，请稍后重试");
            }
            pauseBeforeRetry();
        }
    }

    Duration jitteredTtl(long ttl, long ttlJitter, TimeUnit unit) {
        if (unit == null || ttl <= 0L || ttlJitter < 0L) {
            throw new IllegalArgumentException("Cache TTL and jitter must be valid");
        }
        long baseMillis = Math.max(1L, unit.toMillis(ttl));
        long jitterMillis = Math.max(0L, unit.toMillis(ttlJitter));
        long extraMillis = jitterMillis == 0L
                ? 0L
                : ThreadLocalRandom.current().nextLong(jitterMillis + 1L);
        return Duration.ofMillis(Math.addExact(baseMillis, extraMillis));
    }

    private <R> CacheLookup<R> read(String key, Class<R> type) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return CacheLookup.hit(JSONUtil.toBean(json, type));
        }
        // An empty string is the explicit negative-cache marker.
        return json == null ? CacheLookup.miss() : CacheLookup.hit(null);
    }

    private void setNull(String key) {
        stringRedisTemplate.opsForValue().set(
                key,
                "",
                jitteredTtl(CACHE_NULL_TTL, CACHE_NULL_TTL_JITTER, TimeUnit.MINUTES)
        );
    }

    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                token,
                LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        );
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    private void unlockIfOwner(String key, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_IF_OWNER_SCRIPT, List.of(key), token);
        } catch (RuntimeException error) {
            // The lock still has a short TTL. Do not turn a successful shop
            // read into a failed response merely because cleanup failed.
            log.warn("Unable to release cache rebuild lock {}: {}", key, error.getMessage());
        }
    }

    private void pauseBeforeRetry() {
        long upperBound = Math.max(rebuildRetryMillis + 1L, rebuildRetryMillis * 2L + 1L);
        long pauseMillis = ThreadLocalRandom.current().nextLong(rebuildRetryMillis, upperBound);
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CacheRebuildBusyException("店铺缓存重建等待被中断");
        }
    }

    private record CacheLookup<R>(boolean resolved, R value) {
        static <R> CacheLookup<R> hit(R value) {
            return new CacheLookup<>(true, value);
        }

        static <R> CacheLookup<R> miss() {
            return new CacheLookup<>(false, null);
        }
    }
}
