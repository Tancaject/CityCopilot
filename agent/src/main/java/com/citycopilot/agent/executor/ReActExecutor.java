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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReActExecutor {
    private final ToolGateway gateway;
    private final AgentLlmClient llmClient;
    private final TraceStore traceStore;
    private final int maxSteps;

    public ReActExecutor(ToolGateway gateway, AgentLlmClient llmClient, TraceStore traceStore, int maxSteps) {
        this.gateway = gateway;
        this.llmClient = llmClient;
        this.traceStore = traceStore;
        this.maxSteps = Math.max(1, maxSteps);
    }

    public AgentExecutionResult execute(String message, ToolCallContext context) {
        return execute(message, context, Map.of());
    }

    public AgentExecutionResult execute(String message, ToolCallContext context, Map<String, Object> hints) {
        String traceId = UUID.randomUUID().toString();
        Instant start = Instant.now();
        List<AgentTraceStep> steps = new ArrayList<>();
        Map<String, Integer> duplicateGuard = new HashMap<>();
        String finalAnswer = null;

        for (int index = 0; index < maxSteps; index++) {
            AgentDecision decision = llmClient.next(message, gateway.tools(), List.copyOf(steps), hints == null ? Map.of() : hints);
            if (decision.type() == AgentDecision.Type.FINAL) {
                finalAnswer = decision.finalAnswer();
                steps.add(AgentTraceStep.finalAnswer(index + 1, finalAnswer));
                break;
            }

            String callKey = decision.toolName() + ":" + decision.args();
            int repeated = duplicateGuard.merge(callKey, 1, Integer::sum);
            AgentTraceStep callStep = AgentTraceStep.toolCall(index + 1, decision.toolName(), decision.args());
            steps.add(callStep);
            if (repeated > 2) {
                finalAnswer = "Agent stopped because the same tool call repeated.";
                steps.add(AgentTraceStep.error(index + 1, "duplicate_tool_call", finalAnswer));
                break;
            }

            ToolResult result = gateway.invoke(decision.toolName(), decision.args(), context);
            steps.add(AgentTraceStep.toolResult(index + 1, decision.toolName(), result));
            if (!result.ok() && !result.error().retryable()) {
                finalAnswer = "工具调用失败：" + result.error().message();
                steps.add(AgentTraceStep.finalAnswer(index + 1, finalAnswer));
                break;
            }
        }

        if (finalAnswer == null) {
            finalAnswer = "Agent reached the maximum step limit.";
            steps.add(AgentTraceStep.error(maxSteps, "max_steps", finalAnswer));
        }
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        AgentExecutionResult result = new AgentExecutionResult(traceId, "REACT", List.copyOf(steps), finalAnswer, durationMs);
        traceStore.save(new AgentTrace(traceId, "REACT", result.steps(), finalAnswer, durationMs, Instant.now()));
        return result;
    }
}
