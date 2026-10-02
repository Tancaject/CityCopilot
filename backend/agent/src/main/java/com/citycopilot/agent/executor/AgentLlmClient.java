package com.citycopilot.agent.executor;

import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.tool.ToolMetadata;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 编排层使用的大模型抽象。
 *
 * <p>执行器只依赖结构化决策与文本补全合同，具体实现可调用 OpenAI 兼容接口，也可在模型不可用时采用受限启发式逻辑。</p>
 */
public interface AgentLlmClient {
    /**
     * 根据当前消息、可见工具和已执行观察决定下一步。
     *
     * @param message 包含会话上下文的当前请求
     * @param tools 已按受众和权限过滤的工具元数据
     * @param observations 本次执行已产生的步骤
     * @return 直接回答或单次工具调用
     */
    AgentDecision next(String message, List<ToolMetadata> tools, List<AgentTraceStep> observations);

    /**
     * 支持位置、店铺和长期偏好等额外执行提示的决策入口。
     *
     * @param hints 不直接暴露给工具的编排提示
     */
    default AgentDecision next(
            String message,
            List<ToolMetadata> tools,
            List<AgentTraceStep> observations,
            Map<String, Object> hints
    ) {
        return next(message, tools, observations);
    }

    /**
     * 执行不带工具的文本补全，供批评器、运维诊断等辅助 Agent 使用。
     *
     * @return 完整答案；不支持文本补全的实现返回空串
     */
    default String complete(String systemPrompt, List<String> userMessages) {
        return "";
    }

    /**
     * 流式执行文本补全。
     *
     * <p>默认实现先完成普通补全，再一次性回调，保证不支持原生流式的客户端仍可接入 SSE 链路。</p>
     *
     * @param systemPrompt 系统约束
     * @param userMessages 用户消息序列
     * @param deltaConsumer 增量文本消费者，可为空
     * @return 拼接后的完整答案
     */
    default String completeStreaming(
            String systemPrompt,
            List<String> userMessages,
            Consumer<String> deltaConsumer
    ) {
        String answer = complete(systemPrompt, userMessages);
        if (answer != null && !answer.isEmpty() && deltaConsumer != null) {
            deltaConsumer.accept(answer);
        }
        return answer;
    }
}
