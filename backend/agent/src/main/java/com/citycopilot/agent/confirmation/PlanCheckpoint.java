package com.citycopilot.agent.confirmation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PlanCheckpoint(
        int planVersion,
        String originalMessage,
        Map<String, Object> hints,
        List<PlanCheckpointStep> steps,
        int currentStep,
        Map<Integer, Object> completedOutputs,
        List<String> evidenceIds,
        List<String> verificationSteps
) {
    public PlanCheckpoint {
        planVersion = Math.max(1, planVersion);
        hints = hints == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(hints));
        steps = steps == null ? List.of() : List.copyOf(steps);
        completedOutputs = completedOutputs == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(completedOutputs));
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        verificationSteps = verificationSteps == null ? List.of() : List.copyOf(verificationSteps);
    }
}
