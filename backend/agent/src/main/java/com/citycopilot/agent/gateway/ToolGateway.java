package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolContractSnapshot;
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

/**
 * 基于本地 {@link AgentToolRegistry} 的反射工具网关。
 *
 * <p>负责工具查找、权限校验、参数转换、可靠性保护、异常标准化、审计和指标；它与远程网关实现相同合同，便于模块内测试和同进程部署。</p>
 */
public final class ToolGateway implements ToolGatewayClient {
    /** 保存工具 bean、方法与参数元数据的注册表。 */
    private final AgentToolRegistry registry;
    /** 接收工具调用结果的审计端口。 */
    private final ToolAuditSink auditSink;
    /** 包裹真实反射调用的可靠性保护器。 */
    private final ToolExecutionGuard executionGuard;
    /** 可选的工具耗时和结果指标注册器。 */
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

    /** @return 注册表中全部工具的不可变元数据列表 */
    @Override
    public List<ToolMetadata> tools() {
        return registry.tools();
    }

    /**
     * 校验权限和参数后反射执行本地工具，并将所有失败转换为 {@link ToolResult}。
     *
     * @param name 工具注册名
     * @param args 命名参数；也兼容 {@code arg0} 形式的位置参数
     * @param context 当前用户权限与链路信息
     * @return 成功数据或可分类失败
     */
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

        if (context != null && context.expectedVersion() != null && context.expectedVersion() != tool.contractVersion()) {
            ToolResult result = ToolResult.failure(ToolContractSnapshot.MISMATCH,
                    "工具契约已变化：" + name + "。请重新审阅计划；本次未执行。", false);
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
            // 真实反射调用放进保护器，统一获得限流、熔断、超时与幂等重试语义。
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

    /** 工具声明的所有权限均满足时才允许调用；无上下文不能调用受保护工具。 */
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

    /** 按注册表参数顺序构造反射实参，并在调用前拒绝缺失的必填字段。 */
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

    /** 对 HTTP/JSON 常见的字符串与数字做窄范围类型转换，其余值原样交给方法。 */
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

    /**
     * 记录审计和指标；审计后端失败不得改变已经完成的工具业务结果。
     */
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
