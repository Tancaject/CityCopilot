package com.citycopilot.agent.executor;

import com.citycopilot.agent.gateway.ToolAuditSink;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolExecutionException;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.trace.InMemoryTraceStore;
import com.citycopilot.core.agent.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteExecutorTest {

    @Test
    void refusesHighRiskWriteWithoutPermissionBeforeExecutingReadSteps() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ToolGateway(AgentToolRegistry.fromBeans(List.of(new DemoTools())), ToolAuditSink.noop()),
                traceStore
        );

        AgentExecutionResult result = executor.execute(
                "附近 50 块以内有券火锅，帮我下单",
                new ToolCallContext("anonymous", Set.of()),
                Map.of("x", 120.1, "y", 30.2)
        );

        assertEquals("PLAN_EXECUTE", result.mode());
        assertTrue(result.finalAnswer().contains("需要登录或授权"));
        assertEquals("place_seckill_order", result.steps().get(0).toolName());
        assertTrue(result.steps().stream().noneMatch(step -> "search_nearby_shops".equals(step.toolName())));
        assertTrue(traceStore.find(result.traceId()).isPresent());
    }

    @Test
    void retriesRetryableToolFailureBeforeStoppingPlan() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        RetryOnceGuard guard = new RetryOnceGuard();
        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new DemoTools()));
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ToolGateway(registry, ToolAuditSink.noop(), guard),
                traceStore
        );

        AgentExecutionResult result = executor.execute(
                "附近有什么火锅",
                new ToolCallContext("user-1", Set.of()),
                Map.of("x", 120.1, "y", 30.2)
        );

        assertEquals("计划已执行完成。", result.finalAnswer());
        assertEquals(2, guard.attempts);
    }

    static final class DemoTools {
        @AgentTool(name = "search_nearby_shops", description = "Search nearby shops")
        public List<String> nearby(Integer current, Integer size, Double x, Double y) {
            return List.of("shop");
        }

        @AgentTool(name = "list_vouchers_by_shop", description = "List vouchers")
        public List<String> vouchers(Long shopId) {
            return List.of("voucher-1");
        }

        @AgentTool(name = "place_seckill_order", description = "Place order", requiredPermissions = {"order:write"}, idempotent = false)
        public String order(Long voucherId) {
            return "order-" + voucherId;
        }
    }

    static final class RetryOnceGuard implements com.citycopilot.agent.gateway.ToolExecutionGuard {
        int attempts;

        @Override
        public Object execute(com.citycopilot.agent.tool.ToolMetadata tool, java.util.concurrent.Callable<Object> invocation) throws Exception {
            attempts++;
            if (attempts == 1) {
                throw new ToolExecutionException("rate_limited", "try later", true);
            }
            return invocation.call();
        }
    }
}
