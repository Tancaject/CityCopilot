package com.citycopilot.agent.executor;

import com.citycopilot.agent.trace.AgentTraceStep;

import java.util.List;

public record AgentExecutionResult(
        String traceId,
        String mode,
        List<AgentTraceStep> steps,
        String finalAnswer,
        long durationMs
) {
}
