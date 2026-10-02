package com.citycopilot.eval;

import java.util.List;

/**
 * 单条用例的预测内容、分项判定和运行成本。
 *
 * @param name 用例名称
 * @param passed 所有分项是否通过
 * @param actualMode 实际预测模式
 * @param expectedTools 期望工具
 * @param predictedTools 实际预测工具
 * @param predictedAnswer 实际预测回答
 * @param modePassed 模式断言是否通过
 * @param toolsPassed 工具包含断言是否通过
 * @param keywordsPassed 正向和负向关键词断言是否通过
 * @param estimatedInputTokens 提供方返回或本地估算的输入 token 数
 * @param estimatedOutputTokens 提供方返回或本地估算的输出 token 数
 * @param durationMs 单条预测耗时，单位毫秒
 */
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
        long durationMs,
        List<String> structuredFailures
) {
    public EvalResult(String name, boolean passed, String actualMode, List<String> expectedTools, List<String> predictedTools,
                      String predictedAnswer, boolean modePassed, boolean toolsPassed, boolean keywordsPassed,
                      int estimatedInputTokens, int estimatedOutputTokens, long durationMs) {
        this(name, passed, actualMode, expectedTools, predictedTools, predictedAnswer, modePassed, toolsPassed,
                keywordsPassed, estimatedInputTokens, estimatedOutputTokens, durationMs, List.of());
    }
    /** @return 输入与输出 token 数之和 */
    public int estimatedTotalTokens() {
        return estimatedInputTokens + estimatedOutputTokens;
    }

    /** @return 未通过分项的稳定原因码；通过时为空列表 */
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
        reasons.addAll(structuredFailures);
        return reasons;
    }
}
