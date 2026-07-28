package com.citycopilot.agent.http;

import com.citycopilot.agent.confirmation.InMemoryPendingActionStore;
import com.citycopilot.agent.confirmation.PendingAction;
import com.citycopilot.agent.executor.PlanExecuteExecutor;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolAuditSink;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.memory.InMemoryConversationMemory;
import com.citycopilot.agent.memory.LongTermPreferenceMemory;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.trace.InMemoryTraceStore;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.agent.security.AgentRequestContext;
import com.citycopilot.agent.security.AgentRequestContextHolder;
import com.citycopilot.contract.security.ServiceUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentControllerConfirmationTest {

    @AfterEach
    void clearUser() {
        AgentRequestContextHolder.clear();
    }

    @Test
    void rejectsCrossUserAndRepeatedConfirmation() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        ToolGateway gateway = new ToolGateway(
                AgentToolRegistry.fromBeans(List.of(new ConfirmationTools())),
                ToolAuditSink.noop()
        );
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        AgentController controller = new AgentController(
                null,
                new PlanExecuteExecutor(gateway, traceStore),
                null,
                traceStore,
                store,
                gateway,
                new InMemoryConversationMemory(),
                mock(LongTermPreferenceMemory.class)
        );
        PendingAction action = store.create(
                "confirmed_action",
                Map.of("value", "ok"),
                new ToolCallContext("1", Set.of("*")),
                "trace-1",
                Duration.ofMinutes(5)
        );

        AgentRequestContextHolder.set(context(user(2L)));
        ResponseStatusException forbidden = assertThrows(
                ResponseStatusException.class,
                () -> controller.confirm(action.pendingId())
        );
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        AgentRequestContextHolder.set(context(user(1L)));
        Map<String, Object> result = controller.confirm(action.pendingId());
        assertTrue(Boolean.TRUE.equals(result.get("ok")));
        for (int attempt = 0; attempt < 10; attempt++) {
            ResponseStatusException repeated = assertThrows(
                    ResponseStatusException.class,
                    () -> controller.confirm(action.pendingId())
            );
            assertEquals(HttpStatus.GONE, repeated.getStatusCode());
        }
    }

    private ServiceUser user(Long id) {
        return new ServiceUser(id, "user-" + id, null, "user", Set.of("*"), true);
    }

    private AgentRequestContext context(ServiceUser user) {
        return new AgentRequestContext(user, "Bearer test", "request-test");
    }

    static final class ConfirmationTools {
        @AgentTool(name = "confirmed_action", description = "confirmed action", requiredPermissions = {"order:write"})
        public String execute(String value) {
            return value;
        }
    }
}
