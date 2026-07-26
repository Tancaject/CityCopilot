package com.citycopilot.agent.ops.v2;

import java.util.Map;

public record OpsPlanStep(
        String toolName,
        Map<String, Object> arguments,
        String purpose
) {
    public OpsPlanStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
