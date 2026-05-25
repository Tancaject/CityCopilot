package com.citycopilot.agent.trace;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class RedisTraceStore implements TraceStore {
    private static final String KEY_PREFIX = "agent:trace:";
    private static final String INDEX_KEY = "agent:trace:index";
    private final StringRedisTemplate redisTemplate;

    public RedisTraceStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(AgentTrace trace) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + trace.traceId(), JSONUtil.toJsonStr(trace), Duration.ofDays(7));
            redisTemplate.opsForList().leftPush(INDEX_KEY, trace.traceId());
            redisTemplate.opsForList().trim(INDEX_KEY, 0, 199);
            redisTemplate.expire(INDEX_KEY, Duration.ofDays(7));
        } catch (Exception ignored) {
            // Trace persistence is observability; request handling should not fail when Redis is unavailable.
        }
    }

    @Override
    public Optional<AgentTrace> find(String traceId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + traceId);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(JSONUtil.toBean(json, AgentTrace.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public List<AgentTrace> listRecent(int limit) {
        try {
            List<String> ids = redisTemplate.opsForList().range(INDEX_KEY, 0, Math.max(0, limit - 1));
            if (ids == null) {
                return List.of();
            }
            return ids.stream()
                    .distinct()
                    .map(this::find)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
