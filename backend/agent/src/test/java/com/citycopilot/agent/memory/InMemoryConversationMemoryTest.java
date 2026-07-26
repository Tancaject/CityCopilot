package com.citycopilot.agent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryConversationMemoryTest {

    @Test
    void secondTurnContainsFirstTurnContext() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory();
        memory.appendTurn("session-1", "查看西湖咖啡店的优惠券", "找到了店铺 42 的券");

        String prompt = memory.contextualize("session-1", "就订它");

        assertTrue(prompt.contains("店铺 42"));
        assertTrue(prompt.contains("就订它"));
        assertFalse(memory.contextualize("session-2", "新会话").contains("店铺 42"));
    }
}
