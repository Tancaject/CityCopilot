package com.citycopilot.mcp;

import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.security.AgentSecurity;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class McpToolService {
    private final AgentToolRegistry registry;
    private final ToolGateway gateway;
    private final PermissionPolicy permissionPolicy;

    public McpToolService(AgentToolRegistry registry, ToolGateway gateway, PermissionPolicy permissionPolicy) {
        this.registry = registry;
        this.gateway = gateway;
        this.permissionPolicy = permissionPolicy;
    }

    public List<Map<String, Object>> toolsList() {
        return McpToolMapper.toToolsList(registry.tools().stream()
                .filter(this::isVisibleToCurrentUser)
                .toList());
    }

    public ToolResult call(McpToolCallRequest request) {
        return gateway.invoke(
                request.name(),
                request.arguments() == null ? Map.of() : request.arguments(),
                AgentSecurity.toolContextFrom(UserHolder.getUser(), permissionPolicy)
        );
    }

    private boolean isVisibleToCurrentUser(ToolMetadata tool) {
        if (tool.requiredPermissions().isEmpty()) {
            return true;
        }
        var context = AgentSecurity.toolContextFrom(UserHolder.getUser(), permissionPolicy);
        return tool.requiredPermissions().stream().allMatch(context::hasPermission);
    }
}
