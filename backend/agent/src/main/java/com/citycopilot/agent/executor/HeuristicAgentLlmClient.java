package com.citycopilot.agent.executor;

import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.config.AgentAiProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HeuristicAgentLlmClient implements AgentLlmClient {
    private final AgentAiProperties aiProperties;

    public HeuristicAgentLlmClient(AgentAiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @Override
    public AgentDecision next(String message, List<ToolMetadata> tools, List<AgentTraceStep> observations) {
        return next(message, tools, observations, Map.of());
    }

    @Override
    public AgentDecision next(
            String message,
            List<ToolMetadata> tools,
            List<AgentTraceStep> observations,
            Map<String, Object> hints
    ) {
        if (!observations.isEmpty()) {
            AgentTraceStep last = observations.get(observations.size() - 1);
            if ("tool_result".equals(last.type())) {
                if (last.observation() instanceof ToolResult result && !result.ok()) {
                    String errorMessage = result.error() == null ? "未知错误" : result.error().message();
                    return AgentDecision.finalAnswer("工具调用失败：" + errorMessage);
                }
                return AgentDecision.finalAnswer("已根据工具结果完成查询。");
            }
        }
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "下单", "支付", "退款", "取消订单", "place order", "pay", "refund", "cancel order")) {
            return AgentDecision.finalAnswer(
                    "需要 AI 服务可用并明确识别目标后，才能执行下单、支付、退款或取消订单等写操作。"
            );
        }
        if (containsAny(normalized, "订单", "order")) {
            return AgentDecision.callTool("get_my_orders", Map.of("current", 1));
        }
        if (containsAny(normalized, "优惠券", "券", "voucher")) {
            return AgentDecision.callTool("list_vouchers_by_shop", Map.of("shopId", longHint(hints, "currentShopId", 1L)));
        }
        if (containsAny(normalized, "附近", "火锅", "nearby", "hotpot")) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("current", 1);
            args.put("size", 5);
            args.put("x", doubleHint(hints, "x", 120.149));
            args.put("y", doubleHint(hints, "y", 30.282));
            return AgentDecision.callTool("search_nearby_shops", args);
        }
        return AgentDecision.finalAnswer(fallbackMessage());
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
        Object value = hints == null ? null : hints.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return fallback;
    }

    private Double doubleHint(Map<String, Object> hints, String key, Double fallback) {
        Object value = hints == null ? null : hints.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private String fallbackMessage() {
        String model = aiProperties == null ? null : aiProperties.getModel();
        if (model == null || model.isBlank()) {
            return "我可以帮你查附近店铺、优惠券和订单。";
        }
        return "我可以帮你查附近店铺、优惠券和订单。当前 Agent LLM 模型配置为 " + model + "。";
    }
}
