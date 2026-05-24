package com.citycopilot.agent.gateway;

import java.util.Set;

public record ToolCallContext(String userId, Set<String> permissions) {
    public boolean hasPermission(String permission) {
        return permissions != null && (permissions.contains("*") || permissions.contains(permission));
    }
}
