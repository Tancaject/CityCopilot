package com.citycopilot.contract.security;

import java.util.Set;

public record ServiceUser(
        Long id,
        String nickName,
        String icon,
        String role,
        Set<String> permissions,
        boolean authenticated
) {
    public ServiceUser {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public static ServiceUser anonymous() {
        return new ServiceUser(null, null, null, null, Set.of(), false);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains("*") || permissions.contains(permission);
    }
}
