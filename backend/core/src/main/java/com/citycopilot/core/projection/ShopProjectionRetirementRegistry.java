package com.citycopilot.core.projection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 仅记录成功切换后不再活跃的代际；Redis TIME 同时决定退役时刻和到期条件。 */
@Slf4j
@Component
public class ShopProjectionRetirementRegistry {
    private static final DefaultRedisScript<Long> RETIRE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            return redis.call('ZADD', KEYS[1], now, ARGV[1])
            """, Long.class);
    private static final DefaultRedisScript<List> DUE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local cutoff = t[1] * 1000 + math.floor(t[2] / 1000) - tonumber(ARGV[1])
            local latest = redis.call('ZREVRANGE', KEYS[1], 0, 0)
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', cutoff, 'LIMIT', 0, ARGV[2])
            local candidates = {}
            for _, generation in ipairs(due) do
              if generation ~= latest[1] then table.insert(candidates, generation) end
            end
            return candidates
            """, List.class);
    private final StringRedisTemplate redis;

    public ShopProjectionRetirementRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void recordAfterPublication(String projection, String generation) {
        try {
            redis.execute(RETIRE, List.of(key(projection)), generation);
        } catch (RuntimeException failure) {
            // 发布已经完成，登记失败只会保留旧数据，不能把新代际重新判为未发布。
            log.warn("Projection {} published, but retirement of {} was not recorded; retain it for manual review: {}",
                    projection, generation, failure.getMessage());
        }
    }

    public List<String> due(String projection, Duration retention, int limit) {
        List<?> entries = redis.execute(DUE, List.of(key(projection)),
                Long.toString(retention.toMillis()), Integer.toString(limit));
        if (entries == null) throw new IllegalStateException("Projection retirement query returned no result");
        return entries.stream().map(Object::toString).toList();
    }

    public void forget(String projection, String generation) {
        redis.opsForZSet().remove(key(projection), generation);
    }

    private String key(String projection) {
        return "shop:projection:{retired}:" + projection;
    }
}
