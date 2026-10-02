package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.http.RequestDeadline;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 Core 工具目录适配为 Spring AI 的 {@link ToolCallbackProvider}。
 *
 * <p>每个回调只保存工具定义和转发服务；真正调用仍经 {@link McpToolService} 到达 Core。</p>
 */
@Configuration
public class CityCopilotMcpToolCallbackProvider {

    /**
     * @param service 获取目录并执行远程工具的服务
     * @return 可供 Spring AI MCP Server 发布的动态工具回调提供者
     */
    @Bean
    public ToolCallbackProvider cityCopilotToolCallbackProvider(McpToolService service) {
        return () -> {
            try {
                return service.catalog().stream()
                        .map(tool -> new RemoteToolCallback(tool, service))
                        .map(ToolCallback.class::cast)
                        .toArray(ToolCallback[]::new);
            } catch (Exception unavailable) {
                // Core 目录暂不可用时发布空目录，让 MCP Server 本身仍能启动和恢复。
                return new ToolCallback[0];
            }
        };
    }

    /** 单个共享工具定义对应的远程 ToolCallback 适配器。 */
    private static final class RemoteToolCallback implements ToolCallback {
        /** Core 发布的权威工具元数据。 */
        private final com.citycopilot.contract.tool.ToolDefinition tool;
        /** 执行 audience 检查并转发调用的服务。 */
        private final McpToolService service;
        /** 负责 Tool Schema 和调用结果 JSON 编解码。 */
        private final JsonMapper mapper = JsonMapper.builder().build();

        private RemoteToolCallback(com.citycopilot.contract.tool.ToolDefinition tool, McpToolService service) {
            this.tool = tool;
            this.service = service;
        }

        /** @return Spring AI 使用的工具名、描述与输入 Schema */
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

        /**
         * @param toolInput MCP 客户端传入的 JSON 对象文本
         * @return 序列化后的统一工具响应
         */
        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext context) {
            McpTransportContext transport = McpToolUtils.getMcpExchange(context)
                    .map(exchange -> exchange.transportContext()).orElse(McpTransportContext.EMPTY);
            if (transport.get(McpTransportConfiguration.DEADLINE) instanceof RequestDeadline deadline
                    && transport.get(McpTransportConfiguration.METADATA) instanceof McpRequestMetadata metadata) {
                try (RequestDeadline.Scope ignored = deadline.bind()) {
                    return McpRequestMetadata.withMetadata(metadata, () -> callWithinContext(toolInput));
                }
            }
            return callWithinContext(toolInput);
        }

        private String callWithinContext(String toolInput) {
            try {
                ToolInvocationResponse result = service.call(new McpToolCallRequest(
                        tool.name(), args(toolInput), null, Set.of(), tool.contractVersion()
                ));
                return mapper.writeValueAsString(result);
            } catch (Exception error) {
                // MCP 回调必须返回协议文本，因此把本地解析/转发异常也包装成统一失败响应。
                try {
                    return mapper.writeValueAsString(
                            ToolInvocationResponse.failure(errorCode(error), error.getMessage(), false)
                    );
                } catch (Exception ignored) {
                    return "{\"ok\":false,\"error\":{\"code\":\"mcp_tool_error\"}}";
                }
            }
        }

        private String errorCode(Exception error) {
            if (error instanceof ResponseStatusException response) {
                if (response.getStatusCode().value() == 504) return "mcp_deadline_exceeded";
                if (response.getStatusCode().value() == 503) return "mcp_overloaded";
            }
            return "mcp_tool_error";
        }

        /** @return 解析后的工具实参；空输入规范化为空对象 */
        private Map<String, Object> args(String toolInput) throws Exception {
            if (toolInput == null || toolInput.isBlank()) return Map.of();
            return mapper.readValue(toolInput, new TypeReference<>() {
            });
        }
    }
}
