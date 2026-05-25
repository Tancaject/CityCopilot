package com.citycopilot.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalRunnerTest {

    @Test
    void loadsTenCasesAndRendersReport() {
        EvalRunner runner = new EvalRunner();
        List<EvalCase> cases = runner.loadCases("cases/citycopilot-v01.yaml");
        List<EvalResult> results = runner.run(cases);
        String report = MarkdownReport.render(results);

        assertEquals(10, cases.size());
        assertEquals(10, results.size());
        assertTrue(report.contains("tool_accuracy"));
    }
}
