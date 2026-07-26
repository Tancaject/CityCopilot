package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class CityCopilotMcpToolCallbackProvider {

    @Bean
    public ToolCallbackProvider cityCopilotToolCallbackProvider(McpToolService service) {
        return () -> {
            try {
                return service.catalog().stream()
                        .map(tool -> new RemoteToolCallback(tool, service))
                        .map(ToolCallback.class::cast)
                        .toArray(ToolCallback[]::new);
            } catch (Exception unavailable) {
                return new ToolCallback[0];
            }
        };
    }

    private static final class RemoteToolCallback implements ToolCallback {
        private final com.citycopilot.contract.tool.ToolDefinition tool;
        private final McpToolService service;
        private final ObjectMapper mapper = new ObjectMapper();

        private RemoteToolCallback(com.citycopilot.contract.tool.ToolDefinition tool, McpToolService service) {
            this.tool = tool;
            this.service = service;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            try {
                return ToolDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(mapper.writeValueAsString(McpToolMapper.parametersSchema(tool)))
                        .build();
            } catch (Exception error) {
                throw new IllegalStateException("Unable to build MCP tool definition for " + tool.name(), error);
            }
        }

        @Override
        public String call(String toolInput) {
            try {
                ToolInvocationResponse result = service.call(new McpToolCallRequest(
                        tool.name(), args(toolInput), null, Set.of()
                ));
                return mapper.writeValueAsString(result);
            } catch (Exception error) {
                try {
                    return mapper.writeValueAsString(
                            ToolInvocationResponse.failure("mcp_tool_error", error.getMessage(), false)
                    );
                } catch (Exception ignored) {
                    return "{\"ok\":false,\"error\":{\"code\":\"mcp_tool_error\"}}";
                }
            }
        }

        private Map<String, Object> args(String toolInput) throws Exception {
            if (toolInput == null || toolInput.isBlank()) return Map.of();
            return mapper.readValue(toolInput, new TypeReference<>() {
            });
        }
    }
}
