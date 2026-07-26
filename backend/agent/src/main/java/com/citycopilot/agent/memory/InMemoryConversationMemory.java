package com.citycopilot.agent.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryConversationMemory implements ConversationMemory {
    private static final int MAX_MESSAGES = 12;
    private static final int MAX_CONTEXT_CHARS = 6_000;
    private final Map<String, Deque<ConversationMessage>> sessions = new ConcurrentHashMap<>();

    @Override
    public String contextualize(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return message;
        }
        Deque<ConversationMessage> history = sessions.get(sessionId);
        if (history == null || history.isEmpty()) {
            return message;
        }
        StringBuilder context = new StringBuilder("Conversation history:\n");
        history.forEach(item -> context.append(item.role()).append(": ").append(item.content()).append('\n'));
        context.append("Current user: ").append(message == null ? "" : message);
        if (context.length() <= MAX_CONTEXT_CHARS) {
            return context.toString();
        }
        return context.substring(context.length() - MAX_CONTEXT_CHARS);
    }

    @Override
    public void appendTurn(String sessionId, String userMessage, String assistantMessage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Deque<ConversationMessage> history = sessions.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(new ConversationMessage("user", safe(userMessage)));
            history.addLast(new ConversationMessage("assistant", safe(assistantMessage)));
            while (history.size() > MAX_MESSAGES) {
                history.removeFirst();
            }
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
