package com.citycopilot.agent.trace;

import com.citycopilot.agent.gateway.ToolResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentTraceStep(
        int index,
        String type,
        String toolName,
        Map<String, Object> args,
        Object observation,
        String errorCode,
    String message
) {
    public static AgentTraceStep toolCall(int index, String toolName, Map<String, Object> args) {
        return new AgentTraceStep(index, "tool_call", toolName, copyArgs(args), null, null, null);
    }

    public static AgentTraceStep toolResult(int index, String toolName, ToolResult result) {
        return new AgentTraceStep(index, "tool_result", toolName, Map.of(), result, null, null);
    }

    public static AgentTraceStep finalAnswer(int index, String answer) {
        return new AgentTraceStep(index, "final", null, Map.of(), null, null, answer);
    }

    public static AgentTraceStep error(int index, String errorCode, String message) {
        return new AgentTraceStep(index, "error", null, Map.of(), null, errorCode, message);
    }

    private static Map<String, Object> copyArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
