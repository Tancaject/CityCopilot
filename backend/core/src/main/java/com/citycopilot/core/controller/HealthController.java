package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core 服务健康检查控制器。
 *
 * <p>同时探测 MySQL 与 Redis，并以分项状态说明依赖是否可用；单项异常会被收敛为
 * {@code degraded} 响应，不让探针请求直接抛出异常。</p>
 */
@RestController
public class HealthController {

    /** 用户表访问服务，用一次轻量计数验证数据库连接与查询链路。 */
    @Resource
    private IUserService userService;

    /** Redis 客户端，用于验证缓存的写入和读取能力。 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 汇总当前服务及其关键依赖的健康状态。
     *
     * <p>Redis 探测会写入一个 30 秒后自动过期的 {@code health:ping} 键。</p>
     *
     * @return 包含检查时间、数据库、Redis 和总体状态的成功响应
     */
    @GetMapping("/health")
    public Result health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("time", LocalDateTime.now().toString());
        status.put("database", checkDatabase());
        status.put("redis", checkRedis());
        status.put("status", overall(status));
        return Result.ok(status);
    }

    /**
     * 通过用户表计数查询验证数据库链路，并把异常转换为状态数据。
     *
     * @return {@code up}，或带异常消息的 {@code down} 状态
     */
    private Map<String, Object> checkDatabase() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            userService.countUsers();
            status.put("status", "up");
        } catch (Exception error) {
            status.put("status", "down");
            status.put("message", error.getMessage());
        }
        return status;
    }

    /**
     * 通过短期键的写后读验证 Redis 链路，而非只检查客户端对象是否存在。
     *
     * @return {@code up}，或带异常消息的 {@code down} 状态
     */
    private Map<String, Object> checkRedis() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            String key = "health:ping";
            stringRedisTemplate.opsForValue().set(key, "ok", java.time.Duration.ofSeconds(30));
            status.put("status", "ok".equals(stringRedisTemplate.opsForValue().get(key)) ? "up" : "down");
        } catch (Exception error) {
            status.put("status", "down");
            status.put("message", error.getMessage());
        }
        return status;
    }

    /**
     * 仅当数据库和 Redis 都正常时判定服务完全可用。
     *
     * @param status 已包含依赖分项结果的状态表
     * @return {@code up} 或 {@code degraded}
     */
    private String overall(Map<String, Object> status) {
        Object database = status.get("database");
        Object redis = status.get("redis");
        if (isUp(database) && isUp(redis)) {
            return "up";
        }
        return "degraded";
    }

    /**
     * 安全读取分项检查结果，避免结构异常影响健康接口本身。
     *
     * @param value 某个依赖的检查结果
     * @return 结果结构合法且状态为 {@code up} 时返回 {@code true}
     */
    @SuppressWarnings("unchecked")
    private boolean isUp(Object value) {
        return value instanceof Map<?, ?> map && "up".equals(((Map<String, Object>) map).get("status"));
    }
}
