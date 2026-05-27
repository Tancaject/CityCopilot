package com.citycopilot.core.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取请求头里的 token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true; // 没有 token 也放行，交给 LoginInterceptor 判断
        }
        // 从 Redis 获取用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            return true; // 用户不存在也放行
        }
        // 将 hash 数据转成 UserDTO
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 保存到 ThreadLocal
        UserHolder.saveUser(user);
        // 刷新 token 有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 记录 UV（用用户ID标识唯一访客，按天统计）
        String uvKey = RedisConstants.UV_KEY + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        stringRedisTemplate.opsForHyperLogLog().add(uvKey, user.getId().toString());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求结束后清理 ThreadLocal
        UserHolder.removeUser();
    }
}
