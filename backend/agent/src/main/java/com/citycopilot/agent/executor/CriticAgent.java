package com.citycopilot.agent.executor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CriticAgent {
    private static final String CRITIC_PROMPT = """
            You are CityCopilot's safety critic for high-risk local-life tool calls.
            Review whether the next write operation is safe and clearly authorized by the user intent.
            Reply with exactly one line:
            APPROVE
            or
            REJECT: <short reason>
            Reject if the user intent is ambiguous, required ids are placeholders, or the action changes orders, payments, refunds, publishing, or batch jobs without clear intent.
            """;

    private final AgentLlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public CriticAgent(AgentLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public CriticDecision review(
            String userIntent,
            List<Map<String, Object>> priorSteps,
            String toolName,
            Map<String, Object> args,
            List<String> requiredPermissions
    ) {
        try {
            String response = llmClient.complete(CRITIC_PROMPT, List.of(request(userIntent, priorSteps, toolName, args, requiredPermissions)));
            if (response == null || response.isBlank()) {
                return CriticDecision.reject("critic unavailable");
            }
            String normalized = response.trim();
            if (normalized.equalsIgnoreCase("APPROVE")) {
                return CriticDecision.allow();
            }
            if (normalized.toUpperCase().startsWith("REJECT:")) {
                return CriticDecision.reject(normalized.substring("REJECT:".length()).trim());
            }
            return CriticDecision.reject("unrecognized critic response");
        } catch (Exception e) {
            return CriticDecision.reject(e.getMessage() == null ? "critic failed" : e.getMessage());
        }
    }

    private String request(
            String userIntent,
            List<Map<String, Object>> priorSteps,
            String toolName,
            Map<String, Object> args,
            List<String> requiredPermissions
    ) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userIntent", userIntent == null ? "" : userIntent);
        request.put("priorSteps", priorSteps == null ? List.of() : priorSteps);
        request.put("nextTool", toolName);
        request.put("nextArgs", args == null ? Map.of() : args);
        request.put("requiredPermissions", requiredPermissions == null ? List.of() : requiredPermissions);
        return mapper.writeValueAsString(request);
    }

    public record CriticDecision(boolean approved, String reason) {
        public static CriticDecision allow() {
            return new CriticDecision(true, null);
        }

        public static CriticDecision reject(String reason) {
            return new CriticDecision(false, reason == null || reason.isBlank() ? "rejected" : reason);
        }
    }
}
