package com.citycopilot.agent.gateway;

import java.util.Set;

public record ToolCallContext(
        String userId,
        Set<String> permissions,
        String authorization,
        String requestId,
        String idempotencyKey
) {
    public ToolCallContext(String userId, Set<String> permissions) {
        this(userId, permissions, null, null, null);
    }

    public ToolCallContext {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions != null && (permissions.contains("*") || permissions.contains(permission));
    }

    public ToolCallContext withIdempotencyKey(String value) {
        return new ToolCallContext(userId, permissions, authorization, requestId, value);
    }
}
