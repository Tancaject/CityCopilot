package com.citycopilot.agent.memory;

import com.citycopilot.contract.security.ServiceUser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void anonymousClientsWithTheSameConversationIdDoNotShareHistory() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory();
        String firstClientScope = ConversationScope.sessionKey(
                ServiceUser.anonymous(),
                "shared-client-conversation"
        );
        String secondClientScope = ConversationScope.sessionKey(
                ServiceUser.anonymous(),
                "shared-client-conversation"
        );

        memory.appendTurn(firstClientScope, "我的家庭住址是西湖边", "我记住了");

        assertEquals("推荐附近餐厅", memory.contextualize(secondClientScope, "推荐附近餐厅"));
    }

    @Test
    void authenticatedUserRetainsHistoryAcrossRequests() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory();
        ServiceUser firstRequestUser = new ServiceUser(7L, "user", null, "user", Set.of(), true);
        ServiceUser secondRequestUser = new ServiceUser(7L, "user", null, "user", Set.of(), true);
        String firstRequestScope = ConversationScope.sessionKey(firstRequestUser, "browser-session");
        String secondRequestScope = ConversationScope.sessionKey(secondRequestUser, "browser-session");

        memory.appendTurn(firstRequestScope, "继续推荐咖啡店", "推荐店铺 42");
        String prompt = memory.contextualize(secondRequestScope, "就选它");

        assertEquals(firstRequestScope, secondRequestScope);
        assertTrue(prompt.contains("推荐店铺 42"));
        assertTrue(prompt.contains("就选它"));
    }
}
