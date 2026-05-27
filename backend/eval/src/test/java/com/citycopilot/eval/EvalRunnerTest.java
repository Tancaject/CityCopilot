package com.citycopilot.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalRunnerTest {

    @Test
    void loadsThirtyCasesAndRendersReport() {
        EvalRunner runner = new EvalRunner();
        List<EvalCase> cases = runner.loadCases("cases/citycopilot-v01.yaml");
        List<EvalResult> results = runner.run(cases);
        String report = MarkdownReport.render(results);

        assertEquals(30, cases.size());
        assertEquals(30, results.size());
        assertTrue(report.contains("mode_accuracy"));
        assertTrue(report.contains("tool_accuracy"));
        assertTrue(report.contains("keyword_accuracy"));
        assertTrue(report.contains("avg_estimated_tokens"));
        assertTrue(report.contains("## Failure Reasons"));
        assertTrue(report.contains("- none"));
        assertTrue(results.stream().allMatch(result -> result.estimatedTotalTokens() > 0));
    }

    @Test
    void canRunWithProviderBackedPredictorMetadataAndTokens() {
        EvalPredictor predictor = new EvalPredictor() {
            @Override
            public EvalPrediction predict(EvalCase evalCase) {
                return new EvalPrediction(
                        "REACT",
                        List.of("search_nearby_shops"),
                        "工具: search_nearby_shops | 附近 火锅",
                        12,
                        9
                );
            }

            @Override
            public java.util.Map<String, String> metadata() {
                return java.util.Map.of("provider", "fake-provider", "model", "fake-model");
            }
        };
        EvalRunner runner = new EvalRunner(predictor);
        List<EvalResult> results = runner.run(List.of(new EvalCase(
                "fake",
                "附近 火锅",
                "REACT",
                List.of("search_nearby_shops"),
                List.of("工具"),
                List.of()
        )));
        String report = MarkdownReport.render(results, predictor.metadata());

        assertEquals(1, results.size());
        assertEquals(true, results.get(0).passed());
        assertEquals(21, results.get(0).estimatedTotalTokens());
        assertTrue(report.contains("provider: fake-provider"));
        assertTrue(report.contains("model: fake-model"));
    }

    @Test
    void rendersFailureReasonDistribution() {
        List<EvalResult> results = List.of(
                new EvalResult(
                        "mode_and_tool_failed",
                        false,
                        "REACT",
                        List.of("pay_order"),
                        List.of("search_nearby_shops"),
                        "工具: search_nearby_shops",
                        false,
                        false,
                        true,
                        10,
                        8,
                        5
                ),
                new EvalResult(
                        "keyword_failed",
                        false,
                        "REACT",
                        List.of("search_nearby_shops"),
                        List.of("search_nearby_shops"),
                        "工具: search_nearby_shops",
                        true,
                        true,
                        false,
                        10,
                        8,
                        5
                )
        );

        String report = MarkdownReport.render(results);

        assertTrue(report.contains("- mode_mismatch: 1"));
        assertTrue(report.contains("- tool_mismatch: 1"));
        assertTrue(report.contains("- keyword_mismatch: 1"));
        assertTrue(report.contains("| Failure reasons |"));
        assertTrue(report.contains("mode_mismatch,tool_mismatch"));
    }
}
