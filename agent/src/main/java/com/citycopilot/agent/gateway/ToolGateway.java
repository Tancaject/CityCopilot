package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolParameter;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

public final class ToolGateway {
    private final AgentToolRegistry registry;

    public ToolGateway(AgentToolRegistry registry) {
        this.registry = registry;
    }

    public ToolResult invoke(String name, Map<String, Object> args, ToolCallContext context) {
        ToolMetadata tool;
        try {
            tool = registry.require(name);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("unknown_tool", e.getMessage(), false);
        }

        if (!hasRequiredPermissions(tool, context)) {
            return ToolResult.failure("permission_denied", "Missing permission for tool: " + name, false);
        }

        try {
            Object output = tool.method().invoke(tool.bean(), buildArguments(tool.parameters(), args));
            return ToolResult.success(output);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("invalid_args", e.getMessage(), false);
        } catch (IllegalAccessException e) {
            return ToolResult.failure("tool_inaccessible", e.getMessage(), false);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            return ToolResult.failure("business_error", cause.getMessage(), false);
        }
    }

    private static boolean hasRequiredPermissions(ToolMetadata tool, ToolCallContext context) {
        if (tool.requiredPermissions().isEmpty()) {
            return true;
        }
        if (context == null) {
            return false;
        }
        for (String permission : tool.requiredPermissions()) {
            if (!context.hasPermission(permission)) {
                return false;
            }
        }
        return true;
    }

    private static Object[] buildArguments(List<ToolParameter> parameters, Map<String, Object> args) {
        Object[] values = new Object[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            ToolParameter parameter = parameters.get(i);
            Object raw = args == null ? null : args.getOrDefault(parameter.name(), args.get("arg" + i));
            values[i] = coerce(raw, parameter.javaType());
        }
        return values;
    }

    private static Object coerce(Object raw, Class<?> targetType) {
        if (raw == null || targetType.isInstance(raw)) {
            return raw;
        }
        if (raw instanceof Number number) {
            if (targetType == Long.class || targetType == long.class) {
                return number.longValue();
            }
            if (targetType == Integer.class || targetType == int.class) {
                return number.intValue();
            }
            if (targetType == Double.class || targetType == double.class) {
                return number.doubleValue();
            }
        }
        if (targetType == String.class) {
            return String.valueOf(raw);
        }
        return raw;
    }
}
