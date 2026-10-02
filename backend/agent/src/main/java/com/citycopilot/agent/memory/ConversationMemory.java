package com.citycopilot.agent.memory;

/**
 * Agent 短期会话记忆合同。
 *
 * <p>实现只负责拼接最近轮次与追加问答，不承担长期用户画像；存储不可用时应优先保证聊天主流程可继续。</p>
 */
public interface ConversationMemory {
    /**
     * 将会话历史与当前消息组合为模型输入。
     *
     * @param sessionId 已绑定登录用户的会话键
     * @param message 当前用户消息
     * @return 有历史时的格式化上下文，否则返回原消息
     */
    String contextualize(String sessionId, String message);

    /**
     * 在对话成功收尾后追加一组用户/助手消息。
     *
     * @param sessionId 用户隔离后的会话键
     * @param userMessage 本轮用户输入
     * @param assistantMessage 本轮最终回答
     */
    void appendTurn(String sessionId, String userMessage, String assistantMessage);
}
