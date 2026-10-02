package com.citycopilot.agent.memory;

/**
 * 短期会话中的单条消息。
 *
 * @param role 模型协议角色，目前为 user 或 assistant
 * @param content 已安全处理的消息正文
 */
public record ConversationMessage(String role, String content) {
}
