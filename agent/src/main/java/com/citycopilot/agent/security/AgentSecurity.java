package com.citycopilot.agent.security;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.PermissionPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

public final class AgentSecurity {

    private AgentSecurity() {
    }

    public static ToolCallContext toolContextFrom(UserDTO user, PermissionPolicy permissionPolicy) {
        if (user == null) {
            return new ToolCallContext("anonymous", Set.of());
        }
        return new ToolCallContext(
                user.getId() == null ? "anonymous" : String.valueOf(user.getId()),
                effectivePermissions(user, permissionPolicy)
        );
    }

    public static void requireAuditRead(UserDTO user, PermissionPolicy permissionPolicy) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }
        Set<String> permissions = effectivePermissions(user, permissionPolicy);
        if (!permissions.contains("*") && !permissions.contains(PermissionCodes.AUDIT_READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED");
        }
    }

    private static Set<String> effectivePermissions(UserDTO user, PermissionPolicy permissionPolicy) {
        if (user.getPermissions() != null && !user.getPermissions().isEmpty()) {
            return user.getPermissions();
        }
        if (permissionPolicy == null) {
            return Set.of();
        }
        return permissionPolicy.permissionsForRole(user.getRole());
    }
}
