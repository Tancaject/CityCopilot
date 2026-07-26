package com.citycopilot.agent.executor;

import com.citycopilot.agent.gateway.ToolAuditSink;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolExecutionException;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.confirmation.InMemoryPendingActionStore;
import com.citycopilot.agent.confirmation.PendingAction;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.trace.InMemoryTraceStore;
import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.contract.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteExecutorTest {

    @Test
    void heuristicFallbackRefusesWriteIntentWithoutInventingIdentifiers() {
        DemoTools tools = new DemoTools();
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ScriptedAgentLlmClient(List.of(), List.of()),
                new ToolGateway(AgentToolRegistry.fromBeans(List.of(tools)), ToolAuditSink.noop()),
                new InMemoryTraceStore(),
                0
        );

        AgentExecutionResult result = executor.execute(
                "帮我下单并支付",
                new ToolCallContext("user-1", Set.of("order:write")),
                Map.of()
        );

        assertTrue(result.finalAnswer().contains("需要 AI 服务可用"));
        assertTrue(result.steps().stream().noneMatch(step -> "tool_call".equals(step.type())));
        assertEquals(0, tools.orderAttempts);
    }

    @Test
    void streamingExecutionEmitsToolBeforeDeltas() {
        List<String> eventOrder = new ArrayList<>();
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ScriptedAgentLlmClient(
                        List.of(),
                        List.of(
                                """
                                {"steps":[{"idx":1,"tool":"search_nearby_shops","args":{"current":1,"size":5,"x":120.1,"y":30.2},"highRisk":false}],"finalAnswerTemplate":"done"}
                                """,
                                "结果已生成"
                        )
                ),
                new ToolGateway(AgentToolRegistry.fromBeans(List.of(new DemoTools())), ToolAuditSink.noop()),
                new InMemoryTraceStore(),
                0
        );

        AgentExecutionResult result = executor.executeStreaming(
                "找附近店铺",
                new ToolCallContext("anonymous", Set.of()),
                Map.of(),
                step -> eventOrder.add(step.type()),
                delta -> eventOrder.add("delta")
        );

        assertEquals("tool_call", eventOrder.get(0));
        assertEquals("tool_result", eventOrder.get(1));
        assertEquals("delta", eventOrder.get(2));
        assertEquals("结果已生成", result.finalAnswer());
    }

    @Test
    void refusesHighRiskWriteWithoutPermissionBeforeExecutingReadSteps() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ScriptedAgentLlmClient(List.of(), List.of("""
                        {"steps":[
                          {"idx":1,"tool":"search_nearby_shops","args":{"current":1,"size":5,"x":120.1,"y":30.2},"highRisk":false},
                          {"idx":2,"tool":"place_seckill_order","args":{"voucherId":1},"highRisk":true}
                        ],"finalAnswerTemplate":"done"}
                        """)),
                new ToolGateway(AgentToolRegistry.fromBeans(List.of(new DemoTools())), ToolAuditSink.noop()),
                traceStore,
                2
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
                new ScriptedAgentLlmClient(List.of(), List.of("""
                        {"steps":[
                          {"idx":1,"tool":"search_nearby_shops","args":{"current":1,"size":5,"x":120.1,"y":30.2},"highRisk":false}
                        ],"finalAnswerTemplate":"done"}
                        """, """
                        {"steps":[
                          {"idx":1,"tool":"search_nearby_shops","args":{"current":1,"size":5,"x":120.1,"y":30.2},"highRisk":false}
                        ],"finalAnswerTemplate":"done after replan"}
                        """, "计划已重新规划并完成")),
                new ToolGateway(registry, ToolAuditSink.noop(), guard),
                traceStore,
                2
        );

        AgentExecutionResult result = executor.execute(
                "附近有什么火锅",
                new ToolCallContext("user-1", Set.of()),
                Map.of("x", 120.1, "y", 30.2)
        );

        assertEquals("计划已重新规划并完成", result.finalAnswer());
        assertEquals(2, guard.attempts);
    }

    @Test
    void resolvesReferencesFromEarlierStepsAndUsesFinalAnswerPrompt() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        DemoTools tools = new DemoTools();
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ScriptedAgentLlmClient(List.of(), List.of("""
                        {"steps":[
                          {"idx":1,"tool":"select_shop","args":{},"highRisk":false},
                          {"idx":2,"tool":"list_vouchers_by_shop","args":{"shopId":"$step1.shopId"},"highRisk":false}
                        ],"finalAnswerTemplate":"shop $step1.shopId"}
                        """, "已找到店铺 42 的优惠券")),
                new ToolGateway(AgentToolRegistry.fromBeans(List.of(tools)), ToolAuditSink.noop()),
                traceStore,
                2
        );

        AgentExecutionResult result = executor.execute(
                "先找店铺，然后查优惠券",
                new ToolCallContext("user-1", Set.of()),
                Map.of()
        );

        assertEquals("已找到店铺 42 的优惠券", result.finalAnswer());
        assertEquals(42L, tools.lastVoucherShopId);
        assertTrue(traceStore.find(result.traceId()).isPresent());
    }

    @Test
    void highRiskStepRunsOnlyWhenCriticApproves() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        DemoTools tools = new DemoTools();
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ScriptedAgentLlmClient(List.of(), List.of("""
                        {"steps":[
                          {"idx":1,"tool":"place_seckill_order","args":{"voucherId":7},"highRisk":true}
                        ],"finalAnswerTemplate":"ordered"}
                        """, "APPROVE", "下单已完成")),
                new ToolGateway(AgentToolRegistry.fromBeans(List.of(tools)), ToolAuditSink.noop()),
                traceStore,
                2
        );

        AgentExecutionResult result = executor.execute(
                "帮我下单这个秒杀券",
                new ToolCallContext("user-1", Set.of("order:write")),
                Map.of()
        );

        assertEquals("下单已完成", result.finalAnswer());
        assertEquals(1, tools.orderAttempts);
    }

    @Test
    void highRiskStepStopsWhenCriticRejects() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        DemoTools tools = new DemoTools();
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ScriptedAgentLlmClient(List.of(), List.of("""
                        {"steps":[
                          {"idx":1,"tool":"place_seckill_order","args":{"voucherId":7},"highRisk":true}
                        ],"finalAnswerTemplate":"ordered"}
                        """, "REJECT: missing explicit order confirmation")),
                new ToolGateway(AgentToolRegistry.fromBeans(List.of(tools)), ToolAuditSink.noop()),
                traceStore,
                2
        );

        AgentExecutionResult result = executor.execute(
                "看看这个券",
                new ToolCallContext("user-1", Set.of("order:write")),
                Map.of()
        );

        assertTrue(result.finalAnswer().contains("missing explicit order confirmation"));
        assertEquals("critic_rejected", result.steps().get(1).errorCode());
        assertEquals(0, tools.orderAttempts);
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmationResumesTheOriginalMultiStepPlan() {
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        InMemoryPendingActionStore pendingStore = new InMemoryPendingActionStore();
        DemoTools tools = new DemoTools();
        ToolGateway gateway = new ToolGateway(
                AgentToolRegistry.fromBeans(List.of(tools)),
                ToolAuditSink.noop()
        );
        PlanExecuteExecutor executor = new PlanExecuteExecutor(
                new ScriptedAgentLlmClient(List.of(), List.of("""
                        {"steps":[
                          {"idx":1,"tool":"select_shop","args":{},"highRisk":false},
                          {"idx":2,"tool":"place_seckill_order","args":{"voucherId":"$step1.shopId"},"highRisk":true}
                        ],"finalAnswerTemplate":"ordered"}
                        """, "APPROVE")),
                gateway,
                traceStore,
                0,
                null,
                pendingStore
        );
        ToolCallContext context = new ToolCallContext("user-1", Set.of("order:write"));

        AgentExecutionResult paused = executor.execute("查找并下单", context, Map.of());
        AgentTraceStep confirmation = paused.steps().stream()
                .filter(step -> "confirmation".equals(step.type()))
                .findFirst()
                .orElseThrow();
        String pendingId = String.valueOf(((Map<String, Object>) confirmation.observation()).get("pendingId"));
        PendingAction checkpoint = pendingStore.consume(pendingId).orElseThrow();
        AgentExecutionResult resumed = executor.resume(checkpoint, context);

        assertEquals("已批准的计划执行并复核完成。", resumed.finalAnswer());
        assertEquals(1, tools.orderAttempts);
        assertTrue(checkpoint.completedOutputs().containsKey(1));
        assertEquals(2, checkpoint.steps().size());
    }

    static final class DemoTools {
        Long lastVoucherShopId;
        int orderAttempts;

        @AgentTool(name = "select_shop", description = "Select shop")
        public Map<String, Object> selectShop() {
            return Map.of("shopId", 42L);
        }

        @AgentTool(name = "search_nearby_shops", description = "Search nearby shops")
        public List<String> nearby(Integer current, Integer size, Double x, Double y) {
            return List.of("shop");
        }

        @AgentTool(name = "list_vouchers_by_shop", description = "List vouchers")
        public List<String> vouchers(Long shopId) {
            lastVoucherShopId = shopId;
            return List.of("voucher-1");
        }

        @AgentTool(name = "place_seckill_order", description = "Place order", requiredPermissions = {"order:write"}, idempotent = false)
        public String order(Long voucherId) {
            orderAttempts++;
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
