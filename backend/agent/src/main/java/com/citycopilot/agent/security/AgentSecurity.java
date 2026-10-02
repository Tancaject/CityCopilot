package com.citycopilot.agent.security;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.contract.security.ServiceUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * Agent 控制器与工具调用的集中安全辅助类。
 *
 * <p>只信任 Core 返回的 {@link ServiceUser} 权限，并把身份转换为网关上下文；不存在“从请求参数声明权限”的路径。</p>
 */
public final class AgentSecurity {

    private AgentSecurity() {
    }

    /**
     * 从可信会话构建工具调用上下文。
     *
     * @return 认证用户的权限与链路信息；匿名用户获得空权限集合
     */
    public static ToolCallContext toolContextFrom(ServiceUser user, String authorization, String requestId) {
        if (user == null || !user.authenticated()) {
            return new ToolCallContext("anonymous", Set.of());
        }
        return new ToolCallContext(
                user.id() == null ? "anonymous" : String.valueOf(user.id()),
                user.permissions(),
                authorization,
                requestId,
                null
        );
    }

    /**
     * 要求已认证且有稳定用户 id。
     *
     * @throws ResponseStatusException 不满足时返回 401
     */
    public static ServiceUser requireAuthenticated(ServiceUser user) {
        if (user == null || !user.authenticated() || user.id() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }
        return user;
    }

    public static void requireAuditRead(ServiceUser user) {
        requirePermission(user, PermissionCodes.AUDIT_READ);
    }

    public static void requireOpsAccess(ServiceUser user) {
        requirePermission(user, PermissionCodes.ADMIN_ACCESS);
    }

    /**
     * 要求指定权限或通配权限。
     *
     * @throws ResponseStatusException 匿名为 401，已登录但缺权限为 403
     */
    public static void requirePermission(ServiceUser user, String permission) {
        requireAuthenticated(user);
        Set<String> permissions = user.permissions();
        if (!permissions.contains("*") && !permissions.contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED");
        }
    }

}
