package com.citycopilot.agent.trace;

import java.time.Instant;
import java.util.List;

public record AgentTrace(
        String traceId,
        String mode,
        List<AgentTraceStep> steps,
        String finalAnswer,
        long durationMs,
        Instant createdAt
) {
}
