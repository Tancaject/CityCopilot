package com.citycopilot.core.security;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.SecurityProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class PermissionPolicy {
    private static final Map<String, Set<String>> DEFAULT_ROLE_PERMISSIONS = Map.of(
            "admin", Set.of("*"),
            "geo_operator", Set.of(PermissionCodes.ADMIN_ACCESS, PermissionCodes.GEO_READ, PermissionCodes.GEO_WRITE, PermissionCodes.AUDIT_READ),
            "auditor", Set.of(PermissionCodes.ADMIN_ACCESS, PermissionCodes.GEO_READ, PermissionCodes.AUDIT_READ),
            "merchant", Set.of(PermissionCodes.ADMIN_ACCESS, PermissionCodes.SHOP_WRITE, PermissionCodes.VOUCHER_WRITE, PermissionCodes.ORDER_WRITE)
    );

    @Resource
    private SecurityProperties securityProperties;

    public boolean hasPermission(String role, String permission) {
        if (StrUtil.isBlank(role) || StrUtil.isBlank(permission)) {
            return false;
        }
        Set<String> permissions = permissionsFor(role);
        return permissions.contains("*") || permissions.contains(permission);
    }

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

    public Set<String> permissionsForRole(String role) {
        return permissionsFor(role);
    }

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

    private Map<String, Set<String>> configuredRolePermissions() {
        if (securityProperties == null || securityProperties.getRolePermissions() == null) {
            return Collections.emptyMap();
        }
        return securityProperties.getRolePermissions();
    }
}
