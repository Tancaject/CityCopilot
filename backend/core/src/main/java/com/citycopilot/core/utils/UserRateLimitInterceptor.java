package com.citycopilot.core.utils;

import cn.hutool.json.JSONUtil;
import com.citycopilot.core.dto.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/** 秒杀入口按用户共享令牌：允许突发 5 次，之后每秒补充 1 次。 */
public class UserRateLimitInterceptor implements HandlerInterceptor {
    private static final DefaultRedisScript<Long> TAKE_TOKEN = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
            local updated = tonumber(state[2]) or now
            now = math.max(now, updated)
            local tokens = math.min(5, (tonumber(state[1]) or 5) + (now - updated) / 1000)
            local wait = 0
            if tokens >= 1 then tokens = tokens - 1 else wait = math.ceil((1 - tokens) * 1000) end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now)
            redis.call('PEXPIRE', KEYS[1], 5000)
            return wait
            """, Long.class);
    private final StringRedisTemplate redis;

    public UserRateLimitInterceptor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equals(request.getMethod())) return true;
        Long wait;
        try {
            wait = redis.execute(TAKE_TOKEN, List.of("limit:user:seckill:" + UserHolder.getUser().getId()));
        } catch (DataAccessException error) {
            return reject(response, 503, "RATE_LIMIT_UNAVAILABLE", "秒杀入口限流服务不可用，请稍后重试");
        }
        if (wait == null) return reject(response, 503, "RATE_LIMIT_UNAVAILABLE", "秒杀入口限流服务未返回结果，请稍后重试");
        if (wait == 0) return true;
        long seconds = (wait + 999) / 1000;
        response.setHeader("Retry-After", Long.toString(seconds));
        return reject(response, 429, "RATE_LIMITED", "秒杀请求过于频繁，请在 " + seconds + " 秒后重试");
    }

    private boolean reject(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.fail(code, message)));
        return false;
    }
}
