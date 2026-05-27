package com.citycopilot.mcp;

import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.schema.JsonSchemaGenerator;
import com.citycopilot.agent.security.AgentSecurity;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.utils.UserHolder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class CityCopilotMcpToolCallbackProvider {

    @Bean
    public ToolCallbackProvider cityCopilotToolCallbackProvider(
            AgentToolRegistry registry,
            ToolGateway gateway,
            PermissionPolicy permissionPolicy
    ) {
        List<ToolCallback> callbacks = registry.tools().stream()
                .map(tool -> new GatewayToolCallback(tool, gateway, permissionPolicy))
                .map(ToolCallback.class::cast)
                .toList();
        return ToolCallbackProvider.from(callbacks);
    }

    private static final class GatewayToolCallback implements ToolCallback {
        private final ToolMetadata tool;
        private final ToolGateway gateway;
        private final PermissionPolicy permissionPolicy;
        private final ObjectMapper mapper = new ObjectMapper();

        private GatewayToolCallback(ToolMetadata tool, ToolGateway gateway, PermissionPolicy permissionPolicy) {
            this.tool = tool;
            this.gateway = gateway;
            this.permissionPolicy = permissionPolicy;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            try {
                return ToolDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(mapper.writeValueAsString(JsonSchemaGenerator.parametersSchema(tool)))
                        .build();
            } catch (Exception error) {
                throw new IllegalStateException("Unable to build MCP tool definition for " + tool.name(), error);
            }
        }

        @Override
        public String call(String toolInput) {
            try {
                Map<String, Object> args = args(toolInput);
                ToolResult result = gateway.invoke(
                        tool.name(),
                        args,
                        AgentSecurity.toolContextFrom(UserHolder.getUser(), permissionPolicy)
                );
                return mapper.writeValueAsString(result);
            } catch (Exception error) {
                try {
                    return mapper.writeValueAsString(ToolResult.failure("mcp_tool_error", error.getMessage(), false));
                } catch (Exception ignored) {
                    return "{\"ok\":false,\"error\":{\"code\":\"mcp_tool_error\"}}";
                }
            }
        }

        private Map<String, Object> args(String toolInput) throws Exception {
            if (toolInput == null || toolInput.isBlank()) {
                return Map.of();
            }
            return mapper.readValue(toolInput, new TypeReference<Map<String, Object>>() {
            });
        }
    }
}
