package com.citycopilot.core.controller;

import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationRequest;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.InternalServiceTokenInterceptor;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.tool.CoreToolInvocationService;
import com.citycopilot.core.tool.CoreToolRegistry;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/internal/v1")
public class InternalToolController {
    private final CoreToolRegistry registry;
    private final CoreToolInvocationService invocationService;
    private final PermissionPolicy permissionPolicy;

    public InternalToolController(
            CoreToolRegistry registry,
            CoreToolInvocationService invocationService,
            PermissionPolicy permissionPolicy
    ) {
        this.registry = registry;
        this.invocationService = invocationService;
        this.permissionPolicy = permissionPolicy;
    }

    @GetMapping("/session")
    public ServiceUser session() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return ServiceUser.anonymous();
        }
        Set<String> permissions = user.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            permissions = permissionPolicy.permissionsForRole(user.getRole());
        }
        return new ServiceUser(
                user.getId(),
                user.getNickName(),
                user.getIcon(),
                user.getRole(),
                permissions,
                true
        );
    }

    @GetMapping("/tools")
    public List<ToolDefinition> tools() {
        return registry.definitions();
    }

    @PostMapping("/tools/{toolName}:invoke")
    public ToolInvocationResponse invoke(
            @PathVariable String toolName,
            @RequestBody(required = false) ToolInvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(
                    value = InternalServiceTokenInterceptor.AUTHENTICATED_ACTOR_ATTRIBUTE,
                    required = false
            ) String serviceActor
    ) {
        ToolInvocationRequest safeRequest = request == null
                ? new ToolInvocationRequest(null, null)
                : request;
        return invocationService.invoke(toolName, safeRequest.arguments(), idempotencyKey, serviceActor);
    }
}
