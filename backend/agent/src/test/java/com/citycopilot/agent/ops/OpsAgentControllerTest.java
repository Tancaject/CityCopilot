package com.citycopilot.agent.ops;

import com.citycopilot.agent.security.AgentRequestContext;
import com.citycopilot.agent.security.AgentRequestContextHolder;
import com.citycopilot.contract.security.ServiceUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsAgentControllerTest {

    @AfterEach
    void clearContext() {
        AgentRequestContextHolder.clear();
    }

    @Test
    void rejectsAnonymousAndOrdinaryUsers() {
        OpsAgentController controller = new OpsAgentController(mock(OpsAgentService.class));

        ResponseStatusException anonymous = assertThrows(
                ResponseStatusException.class,
                () -> controller.dailyReport(Map.of())
        );
        assertEquals(401, anonymous.getStatusCode().value());

        AgentRequestContextHolder.set(context(Set.of("order:write")));
        ResponseStatusException ordinary = assertThrows(
                ResponseStatusException.class,
                () -> controller.orderAnomalies(Map.of())
        );
        assertEquals(403, ordinary.getStatusCode().value());
    }

    @Test
    void adminCanEnqueueOpsTask() {
        OpsAgentService service = mock(OpsAgentService.class);
        when(service.enqueue("daily_report", Map.of())).thenReturn(Map.of("queued", true));
        OpsAgentController controller = new OpsAgentController(service);
        AgentRequestContextHolder.set(context(Set.of("admin:access")));

        assertEquals(true, controller.dailyReport(Map.of()).get("queued"));
        verify(service).enqueue("daily_report", Map.of());
    }

    private AgentRequestContext context(Set<String> permissions) {
        return new AgentRequestContext(
                new ServiceUser(7L, "operator", null, "admin", permissions, true),
                "Bearer test",
                "request-1"
        );
    }
}
