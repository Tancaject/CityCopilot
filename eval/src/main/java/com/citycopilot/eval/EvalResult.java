package com.citycopilot.eval;

import java.util.List;

public record EvalResult(
        String name,
        boolean passed,
        String actualMode,
        List<String> expectedTools,
        List<String> predictedTools,
        long durationMs
) {
}
