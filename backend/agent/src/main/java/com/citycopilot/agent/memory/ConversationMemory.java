package com.citycopilot.agent.memory;

public interface ConversationMemory {
    String contextualize(String sessionId, String message);

    void appendTurn(String sessionId, String userMessage, String assistantMessage);
}
