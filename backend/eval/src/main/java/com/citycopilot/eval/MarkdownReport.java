package com.citycopilot.eval;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把评测汇总、分项通过率和逐用例详情渲染为可归档的 Markdown。 */
public final class MarkdownReport {
    private MarkdownReport() {
    }

    /**
     * @param results 全部评测结果
     * @return 使用 offline 元数据的 Markdown 报告
     */
    public static String render(List<EvalResult> results) {
        return render(results, Map.of("provider", "offline"));
    }

    /**
     * @param results 全部评测结果
     * @param metadata 提供方、模型和 API 等报告元数据
     * @return 完整 Markdown 报告
     */
    public static String render(List<EvalResult> results, Map<String, String> metadata) {
        long passed = results.stream().filter(EvalResult::passed).count();
        long modePassed = results.stream().filter(EvalResult::modePassed).count();
        long toolPassed = results.stream().filter(EvalResult::toolsPassed).count();
        long keywordPassed = results.stream().filter(EvalResult::keywordsPassed).count();
        double avgLatency = results.stream().mapToLong(EvalResult::durationMs).average().orElse(0D);
        double avgTokens = results.stream().mapToInt(EvalResult::estimatedTotalTokens).average().orElse(0D);
        StringBuilder builder = new StringBuilder();
        builder.append("# CityCopilot Eval Report\n\n");
        if (metadata != null && !metadata.isEmpty()) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                builder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        builder.append("- cases: ").append(results.size()).append('\n');
        builder.append("- passed: ").append(passed).append('\n');
        builder.append("- mode_accuracy: ").append(percent(modePassed, results.size())).append("%\n");
        builder.append("- tool_accuracy: ").append(percent(toolPassed, results.size())).append("%\n");
        builder.append("- keyword_accuracy: ").append(percent(keywordPassed, results.size())).append("%\n");
        builder.append("- avg_latency_ms: ").append(Math.round(avgLatency)).append('\n');
        builder.append("- avg_estimated_tokens: ").append(Math.round(avgTokens)).append("\n\n");
        appendFailureReasonSummary(builder, results);
        builder.append("| Case | Pass | Mode | Tools | Keywords | Failure reasons | Expected tools | Predicted tools | Latency ms | Est. tokens |\n");
        builder.append("|---|---:|---|---:|---:|---|---|---|---:|---:|\n");
        for (EvalResult result : results) {
            builder.append('|').append(result.name())
                    .append('|').append(result.passed())
                    .append('|').append(result.actualMode())
                    .append('|').append(result.toolsPassed())
                    .append('|').append(result.keywordsPassed())
                    .append('|').append(String.join(",", result.failureReasons()))
                    .append('|').append(String.join(",", result.expectedTools()))
                    .append('|').append(String.join(",", result.predictedTools()))
                    .append('|').append(result.durationMs())
                    .append('|').append(result.estimatedTotalTokens())
                    .append("|\n");
        }
        return builder.toString();
    }

    /** 在明细表之前追加各类失败原因的计数。 */
    private static void appendFailureReasonSummary(StringBuilder builder, List<EvalResult> results) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("mode_mismatch", 0L);
        counts.put("tool_mismatch", 0L);
        counts.put("keyword_mismatch", 0L);
        for (EvalResult result : results) {
            for (String reason : result.failureReasons()) {
                counts.put(reason, counts.getOrDefault(reason, 0L) + 1L);
            }
        }

        builder.append("## Failure Reasons\n\n");
        boolean hasFailures = counts.values().stream().anyMatch(count -> count > 0);
        if (!hasFailures) {
            builder.append("- none\n\n");
            return;
        }
        counts.forEach((reason, count) -> {
            if (count > 0) {
                builder.append("- ").append(reason).append(": ").append(count).append('\n');
            }
        });
        builder.append('\n');
    }

    /** @return 整数百分比；没有用例时返回 0 */
    private static long percent(long count, long total) {
        return total == 0 ? 0 : count * 100 / total;
    }
}
