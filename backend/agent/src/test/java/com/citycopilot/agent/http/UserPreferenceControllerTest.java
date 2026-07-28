package com.citycopilot.agent.http;

import com.citycopilot.agent.memory.LongTermPreferenceMemory;
import com.citycopilot.agent.memory.PreferenceType;
import com.citycopilot.agent.memory.UserPreference;
import com.citycopilot.agent.security.AgentRequestContext;
import com.citycopilot.agent.security.AgentRequestContextHolder;
import com.citycopilot.contract.security.ServiceUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPreferenceControllerTest {

    @AfterEach
    void clearContext() {
        AgentRequestContextHolder.clear();
    }

    @Test
    void listsAndDeletesOnlyForAuthenticatedUser() {
        LongTermPreferenceMemory memory = mock(LongTermPreferenceMemory.class);
        UserPreferenceController controller = new UserPreferenceController(memory);
        Instant now = Instant.now();
        when(memory.list(7L)).thenReturn(List.of(
                new UserPreference(11L, 7L, PreferenceType.PREFERRED_CATEGORY, "火锅", 1, now, now)
        ));
        when(memory.forget(7L, 11L)).thenReturn(true);
        AgentRequestContextHolder.set(context(7L));

        assertEquals(1, controller.list().size());
        assertEquals(true, controller.forget(11L).get("deleted"));
        verify(memory).list(7L);
        verify(memory).forget(7L, 11L);
    }

    @Test
    void rejectsAnonymousAndUnknownPreference() {
        LongTermPreferenceMemory memory = mock(LongTermPreferenceMemory.class);
        UserPreferenceController controller = new UserPreferenceController(memory);

        ResponseStatusException unauthorized = assertThrows(
                ResponseStatusException.class,
                controller::list
        );
        assertEquals(401, unauthorized.getStatusCode().value());

        AgentRequestContextHolder.set(context(7L));
        ResponseStatusException missing = assertThrows(
                ResponseStatusException.class,
                () -> controller.forget(99L)
        );
        assertEquals(404, missing.getStatusCode().value());
    }

    private AgentRequestContext context(long userId) {
        return new AgentRequestContext(
                new ServiceUser(userId, "user-" + userId, null, "user", Set.of(), true),
                "Bearer token",
                "request-1"
        );
    }
}
