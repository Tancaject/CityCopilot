package com.citycopilot.agent.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例短期会话记忆。
 *
 * <p>用于本地开发或无 Redis 环境；每个会话使用有界双端队列，数据不会跨进程共享或在重启后保留。</p>
 */
public final class InMemoryConversationMemory implements ConversationMemory {
    /** 会话键到最近消息队列的并发索引。 */
    private final Map<String, Deque<ConversationMessage>> sessions = new ConcurrentHashMap<>();

    /** 读取当前 JVM 内历史并格式化；无有效会话时保持原消息。 */
    @Override
    public String contextualize(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return message;
        }
        Deque<ConversationMessage> history = sessions.get(sessionId);
        if (history == null || history.isEmpty()) {
            return message;
        }
        return ConversationContextFormatter.format(history, message);
    }

    /** 原子追加一轮问答并从头部裁剪最旧消息。 */
    @Override
    public void appendTurn(String sessionId, String userMessage, String assistantMessage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Deque<ConversationMessage> history = sessions.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(new ConversationMessage("user", ConversationContextFormatter.safe(userMessage)));
            history.addLast(new ConversationMessage("assistant", ConversationContextFormatter.safe(assistantMessage)));
            while (history.size() > ConversationContextFormatter.MAX_MESSAGES) {
                history.removeFirst();
            }
        }
    }
}
