package com.citycopilot.agent.gateway;

import java.util.Map;

public record ToolCallAudit(
        String userId,
        String toolName,
        Map<String, Object> args,
        boolean success,
        ToolError error,
        long durationMs
) {
}
