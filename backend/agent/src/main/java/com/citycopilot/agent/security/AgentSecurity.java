package com.citycopilot.agent.security;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.contract.security.ServiceUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

public final class AgentSecurity {

    private AgentSecurity() {
    }

    public static ToolCallContext toolContextFrom(ServiceUser user, String authorization, String requestId) {
        if (user == null || !user.authenticated()) {
            return new ToolCallContext("anonymous", Set.of());
        }
        return new ToolCallContext(
                user.id() == null ? "anonymous" : String.valueOf(user.id()),
                user.permissions(),
                authorization,
                requestId,
                null
        );
    }

    public static void requireAuditRead(ServiceUser user) {
        if (user == null || !user.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }
        Set<String> permissions = user.permissions();
        if (!permissions.contains("*") && !permissions.contains(PermissionCodes.AUDIT_READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED");
        }
    }

    public static void requireOpsAccess(ServiceUser user) {
        requirePermission(user, PermissionCodes.ADMIN_ACCESS);
    }

    public static void requirePermission(ServiceUser user, String permission) {
        if (user == null || !user.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }
        Set<String> permissions = user.permissions();
        if (!permissions.contains("*") && !permissions.contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED");
        }
    }

}
