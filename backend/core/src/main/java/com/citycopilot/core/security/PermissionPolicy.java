package com.citycopilot.core.security;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.SecurityProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 根据用户角色计算权限并执行 all-of/any-of 判断。
 *
 * <p>配置中的角色权限覆盖内置默认值，便于部署环境收紧或扩展权限而不改代码。</p>
 */
@Component
public class PermissionPolicy {
    /** 未被外部配置覆盖时使用的角色权限基线。 */
    private static final Map<String, Set<String>> DEFAULT_ROLE_PERMISSIONS = Map.of(
            "admin", Set.of("*"),
            "geo_operator", Set.of(PermissionCodes.ADMIN_ACCESS, PermissionCodes.GEO_READ, PermissionCodes.GEO_WRITE, PermissionCodes.AUDIT_READ),
            "auditor", Set.of(PermissionCodes.ADMIN_ACCESS, PermissionCodes.GEO_READ, PermissionCodes.AUDIT_READ),
            "merchant", Set.of(PermissionCodes.ADMIN_ACCESS, PermissionCodes.SHOP_WRITE, PermissionCodes.VOUCHER_WRITE,
                    PermissionCodes.ORDER_WRITE, PermissionCodes.BOOKING_MANAGE, PermissionCodes.BOOKING_CHECK_IN)
    );

    /** 允许部署环境覆盖角色权限的配置。 */
    @Resource
    private SecurityProperties securityProperties;

    /**
     * @param role 用户角色
     * @param permission 待检查权限码
     * @return 角色拥有具体权限或通配权限时为 {@code true}
     */
    public boolean hasPermission(String role, String permission) {
        if (StrUtil.isBlank(role) || StrUtil.isBlank(permission)) {
            return false;
        }
        Set<String> permissions = permissionsFor(role);
        return permissions.contains("*") || permissions.contains(permission);
    }

    /**
     * 检查一组权限；空数组退化为管理端基础权限检查。
     *
     * @param role 用户角色
     * @param permissions 待检查权限码
     * @param anyOf true 表示任一满足，false 表示全部满足
     * @return 是否通过权限组合
     */
    public boolean hasPermissions(String role, String[] permissions, boolean anyOf) {
        if (permissions == null || permissions.length == 0) {
            return hasPermission(role, PermissionCodes.ADMIN_ACCESS);
        }
        for (String permission : permissions) {
            boolean allowed = hasPermission(role, permission);
            if (anyOf && allowed) {
                return true;
            }
            if (!anyOf && !allowed) {
                return false;
            }
        }
        return !anyOf;
    }

    /** @return 合并默认值与配置覆盖后的完整角色权限视图 */
    public Map<String, Set<String>> effectiveRolePermissions() {
        Map<String, Set<String>> effective = new LinkedHashMap<>(DEFAULT_ROLE_PERMISSIONS);
        Map<String, Set<String>> configured = configuredRolePermissions();
        configured.forEach((role, permissions) -> {
            if (StrUtil.isNotBlank(role) && permissions != null) {
                effective.put(role, permissions);
            }
        });
        return effective;
    }

    /**
     * @param role 用户角色
     * @return 该角色当前生效的权限集合
     */
    public Set<String> permissionsForRole(String role) {
        return permissionsFor(role);
    }

    /** @return 配置覆盖优先、默认配置兜底的角色权限集合 */
    private Set<String> permissionsFor(String role) {
        if (StrUtil.isBlank(role)) {
            return Collections.emptySet();
        }
        String normalizedRole = role.trim();
        Set<String> configured = configuredRolePermissions().get(normalizedRole);
        if (configured != null) {
            return configured;
        }
        return DEFAULT_ROLE_PERMISSIONS.getOrDefault(normalizedRole, Collections.emptySet());
    }

    /** @return 非空的角色权限配置；配置尚未注入时返回空映射 */
    private Map<String, Set<String>> configuredRolePermissions() {
        if (securityProperties == null || securityProperties.getRolePermissions() == null) {
            return Collections.emptyMap();
        }
        return securityProperties.getRolePermissions();
    }
}
