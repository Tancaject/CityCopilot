package com.citycopilot.agent.confirmation;

import java.util.LinkedHashMap;
import java.util.Map;

public record PlanCheckpointStep(
        int idx,
        String toolName,
        Map<String, Object> args,
        boolean highRisk
) {
    public PlanCheckpointStep {
        args = args == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(args));
    }
}
