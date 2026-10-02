package com.citycopilot.eval;

import java.util.List;
import java.util.Map;

/**
 * 预测器对单条用例给出的统一结果。
 *
 * @param mode 预测执行模式
 * @param tools 预测会调用的工具
 * @param answer 用于关键词断言的简短回答
 * @param inputTokens 提供方返回的输入 token 数，离线模式可为空
 * @param outputTokens 提供方返回的输出 token 数，离线模式可为空
 */
record EvalPrediction(
        String mode,
        List<String> tools,
        String answer,
        Integer inputTokens,
        Integer outputTokens,
        Map<String, Object> evidence
) {
    EvalPrediction(String mode, List<String> tools, String answer, Integer inputTokens, Integer outputTokens) {
        this(mode, tools, answer, inputTokens, outputTokens, Map.of());
    }
}
