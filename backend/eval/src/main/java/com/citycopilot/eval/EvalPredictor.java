package com.citycopilot.eval;

import java.util.Map;

interface EvalPredictor {
    EvalPrediction predict(EvalCase evalCase);

    default Map<String, String> metadata() {
        return Map.of("provider", "offline");
    }
}
