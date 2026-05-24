package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("/health")
    public Result health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("time", LocalDateTime.now().toString());
        status.put("database", checkDatabase());
        status.put("redis", checkRedis());
        status.put("status", overall(status));
        return Result.ok(status);
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            userService.count();
            status.put("status", "up");
        } catch (Exception error) {
            status.put("status", "down");
            status.put("message", error.getMessage());
        }
        return status;
    }

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

    private String overall(Map<String, Object> status) {
        Object database = status.get("database");
        Object redis = status.get("redis");
        if (isUp(database) && isUp(redis)) {
            return "up";
        }
        return "degraded";
    }

    @SuppressWarnings("unchecked")
    private boolean isUp(Object value) {
        return value instanceof Map<?, ?> map && "up".equals(((Map<String, Object>) map).get("status"));
    }
}
