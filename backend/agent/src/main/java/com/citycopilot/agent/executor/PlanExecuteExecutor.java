package com.citycopilot.agent.executor;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.trace.AgentTrace;
import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.trace.TraceStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlanExecuteExecutor {
    private static final Set<String> HIGH_RISK_TOOLS = Set.of(
            "place_seckill_order",
            "pay_order",
            "cancel_order",
            "refund_order"
    );

    private final ToolGateway gateway;
    private final TraceStore traceStore;

    public PlanExecuteExecutor(ToolGateway gateway, TraceStore traceStore) {
        this.gateway = gateway;
        this.traceStore = traceStore;
    }

    public AgentExecutionResult execute(String message, ToolCallContext context, Map<String, Object> hints) {
        String traceId = UUID.randomUUID().toString();
        Instant start = Instant.now();
        List<AgentTraceStep> steps = new ArrayList<>();
        String finalAnswer = null;

        List<PlanStep> plan = plan(message, hints == null ? Map.of() : hints);
        PlanStep blockedHighRiskStep = firstBlockedHighRiskStep(plan, context);
        if (blockedHighRiskStep != null) {
            finalAnswer = "需要登录或授权后才能执行下单、支付、取消或退款等高风险操作。";
            steps.add(AgentTraceStep.toolCall(1, blockedHighRiskStep.toolName(), blockedHighRiskStep.args()));
            steps.add(AgentTraceStep.error(1, "confirmation_required", finalAnswer));
        } else {
            for (int i = 0; i < plan.size(); i++) {
                PlanStep step = plan.get(i);
                ToolResult result = invokeWithRetry(i + 1, step, context, steps);
                if (!result.ok()) {
                    finalAnswer = "计划执行中止：" + result.error().message();
                    steps.add(AgentTraceStep.finalAnswer(i + 1, finalAnswer));
                    break;
                }
            }
        }

        if (finalAnswer == null) {
            finalAnswer = "计划已执行完成。";
            steps.add(AgentTraceStep.finalAnswer(plan.size() + 1, finalAnswer));
        }
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        AgentExecutionResult result = new AgentExecutionResult(traceId, "PLAN_EXECUTE", List.copyOf(steps), finalAnswer, durationMs);
        traceStore.save(new AgentTrace(traceId, "PLAN_EXECUTE", result.steps(), finalAnswer, durationMs, Instant.now()));
        return result;
    }

    private boolean isHighRiskBlocked(PlanStep step, ToolCallContext context) {
        return HIGH_RISK_TOOLS.contains(step.toolName())
                && (context == null || !context.hasPermission("order:write"));
    }

    private PlanStep firstBlockedHighRiskStep(List<PlanStep> plan, ToolCallContext context) {
        for (PlanStep step : plan) {
            if (isHighRiskBlocked(step, context)) {
                return step;
            }
        }
        return null;
    }

    private ToolResult invokeWithRetry(
            int index,
            PlanStep step,
            ToolCallContext context,
            List<AgentTraceStep> steps
    ) {
        ToolResult result = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            steps.add(AgentTraceStep.toolCall(index, step.toolName(), step.args()));
            result = gateway.invoke(step.toolName(), step.args(), context);
            steps.add(AgentTraceStep.toolResult(index, step.toolName(), result));
            if (result.ok() || result.error() == null || !result.error().retryable() || attempt == 2) {
                return result;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
        return result;
    }

    private List<PlanStep> plan(String message, Map<String, Object> hints) {
        String text = message == null ? "" : message;
        String normalized = text.toLowerCase();
        List<PlanStep> steps = new ArrayList<>();
        if (containsAny(normalized, "下单", "支付", "退款", "取消订单", "place order", "pay", "refund", "cancel order")) {
            steps.add(new PlanStep("search_nearby_shops", nearbyArgs(hints)));
            steps.add(new PlanStep("list_vouchers_by_shop", Map.of("shopId", longHint(hints, "currentShopId", 1L))));
            if (containsAny(normalized, "下单", "place order")) {
                steps.add(new PlanStep("place_seckill_order", Map.of("voucherId", 1L)));
            } else if (containsAny(normalized, "支付", "pay")) {
                steps.add(new PlanStep("pay_order", Map.of("orderId", 1L, "payType", 1)));
            } else if (containsAny(normalized, "退款", "refund")) {
                steps.add(new PlanStep("refund_order", Map.of("orderId", 1L)));
            } else {
                steps.add(new PlanStep("cancel_order", Map.of("orderId", 1L)));
            }
            return steps;
        }
        if (containsAny(normalized, "优惠券", "券", "coupon", "voucher")) {
            return List.of(new PlanStep("list_vouchers_by_shop", Map.of("shopId", longHint(hints, "currentShopId", 1L))));
        }
        return List.of(new PlanStep("search_nearby_shops", nearbyArgs(hints)));
    }

    private Map<String, Object> nearbyArgs(Map<String, Object> hints) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("current", 1);
        args.put("size", 5);
        args.put("x", doubleHint(hints, "x", 120.149));
        args.put("y", doubleHint(hints, "y", 30.282));
        return args;
    }

    private boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private Long longHint(Map<String, Object> hints, String key, Long fallback) {
        Object value = hints.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private Double doubleHint(Map<String, Object> hints, String key, Double fallback) {
        Object value = hints.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private record PlanStep(String toolName, Map<String, Object> args) {
    }
}
