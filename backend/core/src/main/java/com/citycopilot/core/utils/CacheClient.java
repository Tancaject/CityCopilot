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

/**
 * 基于 Redis 的通用 JSON 缓存客户端，重点实现带分布式互斥的 Cache Aside 查询。
 *
 * <p>空值缓存防穿透、TTL 抖动防集中失效；互斥锁只保护缓存重建，不替代数据库事务锁。</p>
 */
@Slf4j
@Component
public class CacheClient {

    /** 仅当 Redis 中的锁值仍等于本请求 token 时删除锁，避免误删他人续接的锁。 */
    static final DefaultRedisScript<Long> UNLOCK_IF_OWNER_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /** 读写缓存、负缓存和互斥锁的 Redis 客户端。 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 未获得重建锁时等待缓存填充的最长时间。 */
    private final long rebuildWaitMillis;
    /** 竞争者两次重试之间的基础退避时间。 */
    private final long rebuildRetryMillis;

    /** @param stringRedisTemplate Redis 操作入口 */
    @org.springframework.beans.factory.annotation.Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this(stringRedisTemplate, CACHE_REBUILD_WAIT_MILLIS, CACHE_REBUILD_RETRY_MILLIS);
    }

    /**
     * 允许测试注入更短的等待和退避时间。
     */
    CacheClient(
            StringRedisTemplate stringRedisTemplate,
            long rebuildWaitMillis,
            long rebuildRetryMillis
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rebuildWaitMillis = Math.max(1L, rebuildWaitMillis);
        this.rebuildRetryMillis = Math.max(1L, rebuildRetryMillis);
    }

    /**
     * 将对象序列化为 JSON，并使用带随机抖动的 TTL 写入 Redis。
     *
     * @param key 完整缓存键
     * @param value 待缓存对象
     * @param ttl 基础有效期
     * @param ttlJitter 额外随机有效期上限
     * @param unit ttl 与抖动的时间单位
     */
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
     * 使用分布式互斥执行 Cache Aside：持锁者双重检查后查询数据库，竞争者短暂等待缓存结果。
     *
     * @param keyPrefix 业务缓存键前缀
     * @param id 追加到键前缀的业务主键
     * @param type JSON 反序列化目标类型
     * @param dbFallback 缓存缺失时的数据库查询函数
     * @param ttl 正常数据基础有效期
     * @param ttlJitter 正常数据随机有效期上限
     * @param unit ttl 与抖动的时间单位
     * @return 缓存或数据库中的对象；权威数据不存在时为 {@code null}
     * @throws CacheRebuildBusyException 等待其他请求重建超时或线程被中断
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
                    // 首次读与抢锁之间可能已有请求填充缓存，双重检查可避免重复查询 MySQL。
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

    /**
     * 把基础 TTL 加上均匀随机抖动，分散大量缓存同时过期的时刻。
     *
     * @return 至少 1 毫秒的最终有效期
     */
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

    /**
     * 区分“键不存在”和“空字符串负缓存”，避免数据库不存在的数据反复穿透。
     */
    private <R> CacheLookup<R> read(String key, Class<R> type) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return CacheLookup.hit(JSONUtil.toBean(json, type));
        }
        // 空字符串是显式负缓存；null 才表示真正 miss。
        return json == null ? CacheLookup.miss() : CacheLookup.hit(null);
    }

    /** 为数据库不存在的数据写入较短且带抖动的负缓存。 */
    private void setNull(String key) {
        stringRedisTemplate.opsForValue().set(
                key,
                "",
                jitteredTtl(CACHE_NULL_TTL, CACHE_NULL_TTL_JITTER, TimeUnit.MINUTES)
        );
    }

    /**
     * 使用随机 token 和 SET NX EX 抢占重建锁。
     *
     * @return 持锁 token；竞争失败时为 {@code null}
     */
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

    /** 通过 Lua 原子校验 token 并释放自己的锁。 */
    private void unlockIfOwner(String key, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_IF_OWNER_SCRIPT, List.of(key), token);
        } catch (RuntimeException error) {
            // 锁有短 TTL；清理失败不应把已经成功的店铺读取改成失败响应。
            log.warn("Unable to release cache rebuild lock {}: {}", key, error.getMessage());
        }
    }

    /** 带随机抖动地等待持锁请求填充缓存，避免竞争者同步轮询。 */
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

    /**
     * 一次缓存读取的三态结果：miss、命中对象或命中负缓存。
     *
     * @param resolved 是否已由缓存给出确定结果
     * @param value 命中对象；负缓存命中时为 {@code null}
     */
    private record CacheLookup<R>(boolean resolved, R value) {
        /** @return 普通命中或负缓存命中 */
        static <R> CacheLookup<R> hit(R value) {
            return new CacheLookup<>(true, value);
        }

        /** @return Redis 键不存在的未解析结果 */
        static <R> CacheLookup<R> miss() {
            return new CacheLookup<>(false, null);
        }
    }
}
