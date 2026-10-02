package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerSseWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpTransportConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    McpServerJsonMapperAutoConfiguration.class,
                    McpServerSseWebMvcAutoConfiguration.class,
                    ToolCallbackConverterAutoConfiguration.class,
                    McpServerAutoConfiguration.class))
            .withUserConfiguration(McpTransportConfiguration.class, CityCopilotMcpToolCallbackProvider.class)
            .withBean(McpCoreProperties.class)
            .withBean(McpCoreClient.class, () -> {
                McpCoreClient client = mock(McpCoreClient.class);
                when(client.tools(any())).thenReturn(List.of(
                        new ToolDefinition("configuration_probe", "Configuration probe", List.of(), 60, true, List.of())));
                return client;
            })
            .withBean(McpToolService.class)
            .withPropertyValues(
                    "spring.ai.mcp.server.type=SYNC",
                    "spring.ai.mcp.server.protocol=SSE",
                    "spring.ai.mcp.server.sse-endpoint=/mcp/sse",
                    "spring.ai.mcp.server.sse-message-endpoint=/mcp/messages");

    @Test
    void assemblesCustomTransportRouterMapperAndServerWithoutCoreConnection() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpServerTransportProvider.class);
            assertThat(context).hasSingleBean(WebMvcSseServerTransportProvider.class);
            assertThat(context).hasSingleBean(McpSyncServer.class);
            assertThat(context).hasSingleBean(RouterFunction.class);
            assertThat(context.getBean("mcpServerJsonMapper")).isInstanceOf(JsonMapper.class);
            assertThat(context).doesNotHaveBean(McpServerSseWebMvcAutoConfiguration.class);
            assertThat(context).doesNotHaveBean("webMvcSseServerRouterFunction");
            assertThat(context.getBean(McpSyncServer.class).listTools())
                    .extracting(tool -> tool.name()).containsExactly("configuration_probe");
            RouterFunction<?> router = context.getBean(RouterFunction.class);
            assertThat(router.route(request("GET", "/mcp/sse"))).isPresent();
            assertThat(router.route(request("POST", "/mcp/messages"))).isPresent();
        });
    }

    @Test
    void disabledServerDoesNotRequireOrRegisterSseBeans() {
        runner.withPropertyValues("spring.ai.mcp.server.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(McpSyncServer.class);
            assertThat(context).doesNotHaveBean(WebMvcSseServerTransportProvider.class);
            assertThat(context).doesNotHaveBean(RouterFunction.class);
        });
    }

    @Test
    void stdioExcludesCustomSseConfigurationWithoutRequiringItsDependencies() {
        // Isolate this condition to avoid starting a standard-input reader in the test process.
        new ApplicationContextRunner().withUserConfiguration(McpTransportConfiguration.class)
                .withPropertyValues("spring.ai.mcp.server.stdio=true", "spring.ai.mcp.server.protocol=SSE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(McpTransportConfiguration.class);
                    assertThat(context).doesNotHaveBean(WebMvcSseServerTransportProvider.class);
                    assertThat(context).doesNotHaveBean(RouterFunction.class);
                });
    }

    @Test
    void streamableProtocolDoesNotRegisterLegacySseTransport() {
        new ApplicationContextRunner().withUserConfiguration(McpTransportConfiguration.class)
                .withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(McpTransportConfiguration.class);
                    assertThat(context).doesNotHaveBean(WebMvcSseServerTransportProvider.class);
                    assertThat(context).doesNotHaveBean(RouterFunction.class);
                });
    }

    private static ServerRequest request(String method, String path) {
        return ServerRequest.create(new MockHttpServletRequest(method, path), List.of());
    }
}
