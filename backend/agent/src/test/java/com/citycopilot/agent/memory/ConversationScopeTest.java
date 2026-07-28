package com.citycopilot.agent.memory;

import com.citycopilot.contract.security.ServiceUser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationScopeTest {

    @Test
    void isolatesTheSameClientConversationAcrossUsers() {
        ServiceUser user1 = new ServiceUser(1L, "one", null, "user", Set.of(), true);
        ServiceUser user2 = new ServiceUser(2L, "two", null, "user", Set.of(), true);

        String first = ConversationScope.sessionKey(user1, "browser-session");
        String sameOwner = ConversationScope.sessionKey(user1, "browser-session");
        String second = ConversationScope.sessionKey(user2, "browser-session");
        String anonymous = ConversationScope.sessionKey(ServiceUser.anonymous(), "browser-session");

        assertEquals(first, sameOwner);
        assertNotEquals(first, second);
        assertNotEquals(first, anonymous);
        assertNull(ConversationScope.sessionKey(user1, " "));
    }
}
