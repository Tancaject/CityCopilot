package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolParameter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolGateway implements ToolGatewayClient {
    private final AgentToolRegistry registry;
    private final ToolAuditSink auditSink;
    private final ToolExecutionGuard executionGuard;
    private final MeterRegistry meterRegistry;

    public ToolGateway(AgentToolRegistry registry) {
        this(registry, ToolAuditSink.noop());
    }

    public ToolGateway(AgentToolRegistry registry, ToolAuditSink auditSink) {
        this(registry, auditSink, ToolExecutionGuard.direct());
    }

    public ToolGateway(AgentToolRegistry registry, ToolAuditSink auditSink, ToolExecutionGuard executionGuard) {
        this(registry, auditSink, executionGuard, null);
    }

    public ToolGateway(
            AgentToolRegistry registry,
            ToolAuditSink auditSink,
            ToolExecutionGuard executionGuard,
            MeterRegistry meterRegistry
    ) {
        this.registry = registry;
        this.auditSink = auditSink == null ? ToolAuditSink.noop() : auditSink;
        this.executionGuard = executionGuard == null ? ToolExecutionGuard.direct() : executionGuard;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<ToolMetadata> tools() {
        return registry.tools();
    }

    @Override
    public ToolResult invoke(String name, Map<String, Object> args, ToolCallContext context) {
        Instant start = Instant.now();
        ToolMetadata tool;
        try {
            tool = registry.require(name);
        } catch (IllegalArgumentException e) {
            ToolResult result = ToolResult.failure("unknown_tool", e.getMessage(), false);
            audit(name, args, context, result, start);
            return result;
        }

        if (!hasRequiredPermissions(tool, context)) {
            ToolResult result = ToolResult.failure("permission_denied", "Missing permission for tool: " + name, false);
            audit(name, args, context, result, start);
            return result;
        }

        try {
            Object[] invocationArgs = buildArguments(tool.parameters(), args);
            Object output = executionGuard.execute(tool, () -> tool.method().invoke(tool.bean(), invocationArgs));
            ToolResult result = ToolResult.success(output);
            audit(name, args, context, result, start);
            return result;
        } catch (ToolExecutionException e) {
            ToolResult result = ToolResult.failure(e.code(), e.getMessage(), e.retryable());
            audit(name, args, context, result, start);
            return result;
        } catch (IllegalArgumentException e) {
            ToolResult result = ToolResult.failure("invalid_args", e.getMessage(), false);
            audit(name, args, context, result, start);
            return result;
        } catch (IllegalAccessException e) {
            ToolResult result = ToolResult.failure("tool_inaccessible", e.getMessage(), false);
            audit(name, args, context, result, start);
            return result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            ToolResult result = ToolResult.failure("business_error", cause.getMessage(), false);
            audit(name, args, context, result, start);
            return result;
        } catch (Exception e) {
            ToolResult result = ToolResult.failure("tool_error", e.getMessage(), false);
            audit(name, args, context, result, start);
            return result;
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
            String positionalName = "arg" + i;
            boolean hasNamedArg = args != null && args.containsKey(parameter.name());
            boolean hasPositionalArg = args != null && args.containsKey(positionalName);
            Object raw = hasNamedArg ? args.get(parameter.name()) : (hasPositionalArg ? args.get(positionalName) : null);
            if (!hasNamedArg && !hasPositionalArg && parameter.required()) {
                throw new IllegalArgumentException("Missing required argument: " + parameter.name());
            }
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
        if (raw instanceof String text) {
            if (targetType == Long.class || targetType == long.class) {
                return Long.parseLong(text);
            }
            if (targetType == Integer.class || targetType == int.class) {
                return Integer.parseInt(text);
            }
            if (targetType == Double.class || targetType == double.class) {
                return Double.parseDouble(text);
            }
            if (targetType == Boolean.class || targetType == boolean.class) {
                return Boolean.parseBoolean(text);
            }
        }
        if (targetType == String.class) {
            return String.valueOf(raw);
        }
        return raw;
    }

    private void audit(String name, Map<String, Object> args, ToolCallContext context, ToolResult result, Instant start) {
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        try {
            auditSink.record(new ToolCallAudit(
                    context == null ? null : context.userId(),
                    name,
                    copyArgs(args),
                    result.ok(),
                    result.ok() ? null : result.error(),
                    durationMs
            ));
        } catch (Exception ignored) {
            // Audit must not turn a successful tool call into an agent-visible failure.
        } finally {
            recordMetrics(name, result, durationMs);
        }
    }

    private void recordMetrics(String name, ToolResult result, long durationMs) {
        if (meterRegistry == null) {
            return;
        }
        String tool = name == null || name.isBlank() ? "unknown" : name;
        String outcome = result.ok() ? "success" : "failure";
        String errorCode = result.ok() || result.error() == null ? "none" : result.error().code();
        Timer.builder("citycopilot.tool.call.duration")
                .description("ToolGateway tool call duration")
                .tag("tool", tool)
                .tag("outcome", outcome)
                .tag("error", errorCode)
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0L, durationMs)));
        Counter.builder("citycopilot.tool.call.count")
                .description("ToolGateway tool call count")
                .tag("tool", tool)
                .tag("outcome", outcome)
                .tag("error", errorCode)
                .register(meterRegistry)
                .increment();
    }

    private static Map<String, Object> copyArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
