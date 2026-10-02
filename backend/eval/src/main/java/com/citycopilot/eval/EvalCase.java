package com.citycopilot.eval;

import java.util.List;
import java.util.Map;

/**
 * 从评测 YAML 读取的单条期望。
 *
 * @param name 用例名称
 * @param input 发送给路由器或模型的用户输入
 * @param expectMode 期望的 REACT 或 PLAN_EXECUTE 模式
 * @param expectTools 预测结果必须包含的工具名
 * @param expectKeywords 回答必须包含的关键词
 * @param expectNoKeywords 回答不得包含的关键词
 */
public record EvalCase(
        String name,
        String input,
        String expectMode,
        List<String> expectTools,
        List<String> expectKeywords,
        List<String> expectNoKeywords,
        Map<String, Object> booking
) {
    public EvalCase(String name, String input, String expectMode, List<String> expectTools,
                    List<String> expectKeywords, List<String> expectNoKeywords) {
        this(name, input, expectMode, expectTools, expectKeywords, expectNoKeywords, Map.of());
    }
}
