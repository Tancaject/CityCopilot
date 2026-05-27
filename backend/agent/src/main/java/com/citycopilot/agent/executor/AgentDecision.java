package com.citycopilot.agent.executor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentDecision(Type type, String finalAnswer, String toolName, Map<String, Object> args) {
    public enum Type {
        FINAL,
        TOOL_CALL
    }

    public static AgentDecision finalAnswer(String answer) {
        return new AgentDecision(Type.FINAL, answer, null, Map.of());
    }

    public static AgentDecision callTool(String toolName, Map<String, Object> args) {
        return new AgentDecision(Type.TOOL_CALL, null, toolName, copyArgs(args));
    }

    private static Map<String, Object> copyArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
