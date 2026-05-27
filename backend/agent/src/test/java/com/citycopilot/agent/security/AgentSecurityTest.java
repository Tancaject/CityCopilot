package com.citycopilot.agent.security;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.PermissionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSecurityTest {

    private final PermissionPolicy permissionPolicy = new PermissionPolicy();

    @Test
    void buildsToolContextFromCapturedUserRole() {
        UserDTO user = new UserDTO();
        user.setId(42L);
        user.setRole("merchant");

        ToolCallContext context = AgentSecurity.toolContextFrom(user, permissionPolicy);

        assertEquals("42", context.userId());
        assertTrue(context.hasPermission("order:write"));
    }

    @Test
    void requiresAuditReadForTraceAccess() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setRole("merchant");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> AgentSecurity.requireAuditRead(user, permissionPolicy)
        );

        assertEquals(403, error.getStatusCode().value());
    }

    @Test
    void allowsExplicitAuditReadForTraceAccess() {
        UserDTO user = new UserDTO();
        user.setId(8L);
        user.setPermissions(Set.of("audit:read"));

        AgentSecurity.requireAuditRead(user, permissionPolicy);
    }
}
