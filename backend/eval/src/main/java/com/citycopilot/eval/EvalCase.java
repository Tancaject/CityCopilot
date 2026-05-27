package com.citycopilot.eval;

import java.util.List;

public record EvalCase(
        String name,
        String input,
        String expectMode,
        List<String> expectTools,
        List<String> expectKeywords,
        List<String> expectNoKeywords
) {
}
