package com.citycopilot.agent.security;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.contract.security.ServiceUser;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSecurityTest {

    @Test
    void buildsToolContextFromCoreSession() {
        ServiceUser user = new ServiceUser(42L, "merchant", null, "merchant", Set.of("order:write"), true);

        ToolCallContext context = AgentSecurity.toolContextFrom(user, "Bearer token", "request-1");

        assertEquals("42", context.userId());
        assertEquals("Bearer token", context.authorization());
        assertEquals("request-1", context.requestId());
        assertTrue(context.hasPermission("order:write"));
    }

    @Test
    void requiresAuditReadForTraceAccess() {
        ServiceUser user = new ServiceUser(7L, "merchant", null, "merchant", Set.of("order:read"), true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> AgentSecurity.requireAuditRead(user)
        );

        assertEquals(403, error.getStatusCode().value());
    }

    @Test
    void allowsExplicitAuditReadForTraceAccess() {
        ServiceUser user = new ServiceUser(8L, "auditor", null, "admin", Set.of("audit:read"), true);
        AgentSecurity.requireAuditRead(user);
    }

    @Test
    void rejectsOrdinaryUserForOpsAccess() {
        ServiceUser user = new ServiceUser(9L, "user", null, "user", Set.of("order:write"), true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> AgentSecurity.requireOpsAccess(user)
        );

        assertEquals(403, error.getStatusCode().value());
    }

    @Test
    void allowsAdminForOpsAccess() {
        ServiceUser user = new ServiceUser(10L, "operator", null, "admin", Set.of("admin:access"), true);
        AgentSecurity.requireOpsAccess(user);
    }
}
