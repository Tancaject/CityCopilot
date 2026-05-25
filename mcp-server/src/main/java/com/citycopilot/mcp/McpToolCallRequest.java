package com.citycopilot.mcp;

import java.util.Map;
import java.util.Set;

public record McpToolCallRequest(
        String name,
        Map<String, Object> arguments,
        String userId,
        Set<String> permissions
) {
}
