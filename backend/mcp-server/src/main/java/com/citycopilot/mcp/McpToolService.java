package com.citycopilot.mcp;

import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class McpToolService {
    private final McpCoreClient coreClient;

    public McpToolService(McpCoreClient coreClient) {
        this.coreClient = coreClient;
    }

    public List<ToolDefinition> availableTools() {
        McpRequestMetadata metadata = McpRequestMetadata.current();
        ServiceUser user = coreClient.session(metadata);
        return catalog(metadata).stream()
                .filter(this::availableToMcp)
                .filter(tool -> tool.requiredPermissions().isEmpty()
                        || tool.requiredPermissions().stream().allMatch(user::hasPermission))
                .toList();
    }

    public List<ToolDefinition> catalog() {
        return catalog(McpRequestMetadata.current()).stream()
                .filter(this::availableToMcp)
                .toList();
    }

    public List<Map<String, Object>> toolsList() {
        return McpToolMapper.toToolsList(availableTools());
    }

    public ToolInvocationResponse call(McpToolCallRequest request) {
        List<ToolDefinition> definitions = catalog();
        boolean allowed = definitions.isEmpty()
                || definitions.stream().anyMatch(tool -> tool.name().equals(request.name()));
        if (!allowed) {
            return ToolInvocationResponse.failure(
                    "tool_not_allowed",
                    "Tool is not available to MCP: " + request.name(),
                    false
            );
        }
        return coreClient.invoke(
                request.name(),
                request.arguments() == null ? Map.of() : request.arguments(),
                McpRequestMetadata.current()
        );
    }

    private List<ToolDefinition> catalog(McpRequestMetadata metadata) {
        return coreClient.tools(metadata);
    }

    private boolean availableToMcp(ToolDefinition tool) {
        return tool.audience() == ToolAudience.ALL || tool.audience() == ToolAudience.MCP;
    }
}
