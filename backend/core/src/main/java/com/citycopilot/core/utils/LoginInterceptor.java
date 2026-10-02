package com.citycopilot.core.utils;

import cn.hutool.json.JSONUtil;
import com.citycopilot.core.dto.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/** 保护必须登录的 MVC 路由；用户上下文由 {@link RefreshTokenInterceptor} 提前恢复。 */
public class LoginInterceptor implements HandlerInterceptor {

    /** @return 已登录时放行；否则写入 401 统一响应并终止调用链 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSONUtil.toJsonStr(Result.fail("AUTH_REQUIRED", "请先登录")));
            return false;
        }
        return true;
    }
}
