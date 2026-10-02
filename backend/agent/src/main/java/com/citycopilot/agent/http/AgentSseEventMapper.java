package com.citycopilot.agent.http;

import com.citycopilot.agent.executor.AgentExecutionResult;
import com.citycopilot.agent.trace.AgentTraceStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将内部 Trace 步骤映射为稳定的前端 SSE 事件协议。
 *
 * <p>集中处理工具、确认、错误和最终答案载荷，避免 ReAct 与 Plan-Execute 两条链路产生不同前端格式。</p>
 */
public final class AgentSseEventMapper {

    private AgentSseEventMapper() {
    }

    /**
     * 把已完成执行结果展开为按顺序可发送的 SSE 事件。
     *
     * @param result 含 Trace 步骤的执行结果
     * @return 首项为 traceId，随后为步骤事件，并确保最多补充一个最终答案事件
     */
    public static List<AgentSseEvent> toEvents(AgentExecutionResult result) {
        List<AgentSseEvent> events = new ArrayList<>();
        events.add(new AgentSseEvent("traceId", Map.of("traceId", result.traceId(), "mode", result.mode())));
        boolean hasFinal = false;
        for (AgentTraceStep step : result.steps()) {
            switch (step.type()) {
                case "tool_call", "tool_result" -> events.add(new AgentSseEvent("tool", toolPayload(step)));
                case "confirmation" -> events.add(new AgentSseEvent("confirmation", confirmationPayload(step)));
                case "error" -> events.add(new AgentSseEvent("error", Map.of(
                        "code", step.errorCode(),
                        "message", step.message()
                )));
                case "final" -> events.add(new AgentSseEvent("final", Map.of(
                        "message", step.message(),
                        "traceId", result.traceId(),
                        "durationMs", result.durationMs()
                )));
                default -> {
                }
            }
            if ("final".equals(step.type())) {
                hasFinal = true;
            }
        }
        if (!hasFinal && result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            events.add(new AgentSseEvent("final", Map.of(
                    "message", result.finalAnswer(),
                    "traceId", result.traceId(),
                    "durationMs", result.durationMs()
            )));
        }
        return events;
    }

    /**
     * 映射流式执行期间产生的单个中间步骤。
     *
     * @return 工具、确认或错误事件；最终答案等非中间步骤返回 null
     */
    public static AgentSseEvent fromStep(AgentTraceStep step) {
        return switch (step.type()) {
            case "tool_call", "tool_result" -> new AgentSseEvent("tool", toolPayload(step));
            case "confirmation" -> new AgentSseEvent("confirmation", confirmationPayload(step));
            case "error" -> new AgentSseEvent("error", Map.of(
                    "code", step.errorCode(),
                    "message", step.message()
            ));
            default -> null;
        };
    }

    /** 合并确认步骤的基础工具字段与 pendingId、过期时间等扩展信息。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> confirmationPayload(AgentTraceStep step) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", step.toolName());
        payload.put("args", step.args());
        if (step.observation() instanceof Map<?, ?> details) {
            details.forEach((key, value) -> payload.put(String.valueOf(key), value));
        }
        return payload;
    }

    private static Map<String, Object> toolPayload(AgentTraceStep step) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("index", step.index());
        payload.put("phase", step.type().equals("tool_call") ? "call" : "result");
        payload.put("toolName", step.toolName());
        payload.put("args", step.args());
        payload.put("observation", step.observation());
        return payload;
    }
}
