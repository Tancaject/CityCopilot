package com.citycopilot.agent.http;

import com.citycopilot.agent.executor.AgentExecutionResult;
import com.citycopilot.agent.trace.AgentTraceStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentSseEventMapper {

    private AgentSseEventMapper() {
    }

    public static List<AgentSseEvent> toEvents(AgentExecutionResult result) {
        List<AgentSseEvent> events = new ArrayList<>();
        events.add(new AgentSseEvent("traceId", Map.of("traceId", result.traceId(), "mode", result.mode())));
        boolean hasFinal = false;
        for (AgentTraceStep step : result.steps()) {
            switch (step.type()) {
                case "tool_call", "tool_result" -> events.add(new AgentSseEvent("tool", toolPayload(step)));
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
