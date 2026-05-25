package com.citycopilot.agent.tool;

import java.lang.reflect.Method;
import java.util.List;

public record ToolMetadata(
        String name,
        String description,
        List<String> requiredPermissions,
        int rateLimitPerMinute,
        boolean idempotent,
        Object bean,
        Class<?> beanType,
        Method method,
        List<ToolParameter> parameters
) {
}
