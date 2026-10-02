package com.citycopilot.core.utils;

import cn.hutool.json.JSONUtil;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 管理端 MVC 权限拦截器。
 *
 * <p>优先读取方法上的 {@link RequirePermission}，再读取类级声明；没有声明时要求基础管理权限。</p>
 */
public class AdminInterceptor implements HandlerInterceptor {

    /** 将角色映射为权限并执行组合判断的策略。 */
    private final PermissionPolicy permissionPolicy;

    /** @param permissionPolicy 角色权限策略 */
    public AdminInterceptor(PermissionPolicy permissionPolicy) {
        this.permissionPolicy = permissionPolicy;
    }

    /**
     * 在控制器执行前校验登录态与所需权限。
     *
     * @return 允许执行时为 {@code true}；拒绝时已写入统一 JSON 响应
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            writeError(response, 401, "AUTH_REQUIRED", "请先登录");
            return false;
        }
        RequirePermission permission = resolvePermission(handler);
        boolean allowed = permission == null
                ? permissionPolicy.hasPermission(user.getRole(), PermissionCodes.ADMIN_ACCESS)
                : permissionPolicy.hasPermissions(user.getRole(), permission.value(), permission.anyOf());
        if (!allowed) {
            writeError(response, 403, "PERMISSION_DENIED", "没有权限执行该操作");
            return false;
        }
        return true;
    }

    /** @return 方法级权限声明、类级声明或 {@code null} */
    private RequirePermission resolvePermission(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        RequirePermission methodPermission = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(),
                RequirePermission.class
        );
        if (methodPermission != null) {
            return methodPermission;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(),
                RequirePermission.class
        );
    }

    /** 写入统一的 UTF-8 权限错误响应。 */
    private void writeError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.fail(code, message)));
    }
}
