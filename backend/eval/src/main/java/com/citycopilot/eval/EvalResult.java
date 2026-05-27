package com.citycopilot.eval;

import java.util.List;

public record EvalResult(
        String name,
        boolean passed,
        String actualMode,
        List<String> expectedTools,
        List<String> predictedTools,
        String predictedAnswer,
        boolean modePassed,
        boolean toolsPassed,
        boolean keywordsPassed,
        int estimatedInputTokens,
        int estimatedOutputTokens,
        long durationMs
) {
    public int estimatedTotalTokens() {
        return estimatedInputTokens + estimatedOutputTokens;
    }

    public List<String> failureReasons() {
        if (passed) {
            return List.of();
        }
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        if (!modePassed) {
            reasons.add("mode_mismatch");
        }
        if (!toolsPassed) {
            reasons.add("tool_mismatch");
        }
        if (!keywordsPassed) {
            reasons.add("keyword_mismatch");
        }
        return reasons;
    }
}
