package com.citycopilot.agent.trace;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Redis 的共享 Trace 存储。
 *
 * <p>轨迹正文按 id 保存，最近索引使用有界 List，两者保留七天；Redis 故障按可观测性降级处理，不让用户请求因 Trace 写入失败。</p>
 */
public final class RedisTraceStore implements TraceStore {
    /** 单条轨迹正文键前缀。 */
    private static final String KEY_PREFIX = "agent:trace:";
    /** 最近轨迹 id 的有界列表键。 */
    private static final String INDEX_KEY = "agent:trace:index";
    /** Redis 字符串与列表操作客户端。 */
    private final StringRedisTemplate redisTemplate;

    public RedisTraceStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 尽力写入轨迹正文和最近索引，并刷新七天 TTL。 */
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

    /** 读取并反序列化轨迹；缺失、过期或 Redis 异常时为空。 */
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

    /** 根据最近 id 索引批量解析轨迹，并去除重复保存产生的重复 id。 */
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
