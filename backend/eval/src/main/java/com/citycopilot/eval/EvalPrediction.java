package com.citycopilot.eval;

import java.util.List;

record EvalPrediction(
        String mode,
        List<String> tools,
        String answer,
        Integer inputTokens,
        Integer outputTokens
) {
}
