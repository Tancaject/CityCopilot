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

/**
 * 尝试从 Redis 会话恢复用户上下文，并滑动刷新会话 TTL。
 *
 * <p>它对匿名请求始终放行，具体接口是否要求登录由后续 {@link LoginInterceptor} 决定；
 * 请求线程上下文由外层 {@link UserContextFilter} 清理，包括此拦截器尚未返回时的异常。</p>
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    /** 读取会话 Hash、刷新 TTL 并记录 UV 的 Redis 客户端。 */
    private final StringRedisTemplate stringRedisTemplate;

    /** @param stringRedisTemplate Redis 操作入口 */
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true; // 匿名请求继续执行，受保护接口由 LoginInterceptor 统一拒绝。
        }
        // 会话以 Hash 保存最小 UserDTO，键由登录 token 唯一定位。
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            return true; // token 失效等同匿名，仍交给具体路由的登录策略判断。
        }
        // 将 Redis Hash 恢复为只包含展示与授权字段的会话 DTO。
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 后续拦截器和业务服务通过 UserHolder 读取同一请求的用户。
        UserHolder.saveUser(user);
        // 每次有效访问滑动刷新 TTL，保持活跃用户会话。
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // HyperLogLog 按用户 ID 近似统计每日独立访客，避免存储完整访问集合。
        String uvKey = RedisConstants.UV_KEY + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        stringRedisTemplate.opsForHyperLogLog().add(uvKey, user.getId().toString());
        return true;
    }
}
