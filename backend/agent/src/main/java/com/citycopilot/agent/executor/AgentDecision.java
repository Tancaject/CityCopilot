package com.citycopilot.agent.executor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型在单轮推理中给出的下一步决策。
 *
 * @param type 直接回答或调用工具
 * @param finalAnswer 直接回答文本，仅 {@link Type#FINAL} 使用
 * @param toolName 工具注册名，仅 {@link Type#TOOL_CALL} 使用
 * @param args 工具参数的不可变副本
 */
public record AgentDecision(Type type, String finalAnswer, String toolName, Map<String, Object> args) {
    /** 模型决策的两种可执行形态。 */
    public enum Type {
        /** 结束 Agent 循环并向用户返回答案。 */
        FINAL,
        /** 交给 ToolGateway 执行一次结构化工具调用。 */
        TOOL_CALL
    }

    /** @return 不再调用工具的终止决策 */
    public static AgentDecision finalAnswer(String answer) {
        return new AgentDecision(Type.FINAL, answer, null, Map.of());
    }

    /**
     * 创建结构化工具调用决策。
     *
     * @param toolName 工具注册名
     * @param args 模型生成的参数
     * @return 参数已防御性复制的调用决策
     */
    public static AgentDecision callTool(String toolName, Map<String, Object> args) {
        return new AgentDecision(Type.TOOL_CALL, null, toolName, copyArgs(args));
    }

    private static Map<String, Object> copyArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
