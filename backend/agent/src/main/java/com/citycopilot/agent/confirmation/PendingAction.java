package com.citycopilot.agent.confirmation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PendingAction(
        String pendingId,
        String toolName,
        Map<String, Object> args,
        String userId,
        String traceId,
        String summary,
        Instant expiresAt,
        int planVersion,
        String planHash,
        String originalMessage,
        Map<String, Object> hints,
        List<PlanCheckpointStep> steps,
        int currentStep,
        Map<Integer, Object> completedOutputs,
        List<String> evidenceIds,
        List<String> verificationSteps
) {
}
