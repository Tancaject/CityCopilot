package com.citycopilot.eval;

import java.util.List;

public final class MarkdownReport {
    private MarkdownReport() {
    }

    public static String render(List<EvalResult> results) {
        long passed = results.stream().filter(EvalResult::passed).count();
        StringBuilder builder = new StringBuilder();
        builder.append("# CityCopilot Eval Report\n\n");
        builder.append("- cases: ").append(results.size()).append('\n');
        builder.append("- passed: ").append(passed).append('\n');
        builder.append("- tool_accuracy: ").append(results.isEmpty() ? 0 : (passed * 100 / results.size())).append("%\n\n");
        builder.append("| Case | Pass | Mode | Expected tools | Predicted tools | Latency ms |\n");
        builder.append("|---|---:|---|---|---|---:|\n");
        for (EvalResult result : results) {
            builder.append('|').append(result.name())
                    .append('|').append(result.passed())
                    .append('|').append(result.actualMode())
                    .append('|').append(String.join(",", result.expectedTools()))
                    .append('|').append(String.join(",", result.predictedTools()))
                    .append('|').append(result.durationMs())
                    .append("|\n");
        }
        return builder.toString();
    }
}
