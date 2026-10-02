package com.citycopilot.mcp;

import com.citycopilot.http.DeadlineHttpRequestFactory;
import com.citycopilot.http.RequestDeadline;
import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Map;

/** Capture POST metadata before SDK dispatch; an SSE session itself has no operation deadline. */
@Configuration
@ConditionalOnExpression("${spring.ai.mcp.server.enabled:true} && !${spring.ai.mcp.server.stdio:false}")
@ConditionalOnProperty(name = "spring.ai.mcp.server.protocol", havingValue = "SSE")
@EnableConfigurationProperties(McpServerSseProperties.class)
public class McpTransportConfiguration {
    static final String DEADLINE = McpTransportConfiguration.class.getName() + ".deadline";
    static final String METADATA = McpTransportConfiguration.class.getName() + ".metadata";

    @Bean
    WebMvcSseServerTransportProvider mcpDeadlineTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper,
            McpServerSseProperties server, McpCoreProperties core) {
        return WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .baseUrl(server.getBaseUrl())
                .sseEndpoint(server.getSseEndpoint())
                .messageEndpoint(server.getSseMessageEndpoint())
                .keepAliveInterval(server.getKeepAliveInterval())
                .contextExtractor(request -> capture(request, core))
                .build();
    }

    @Bean
    RouterFunction<ServerResponse> mcpDeadlineRouter(WebMvcSseServerTransportProvider transport) {
        // Providing the transport disables the stock transport auto-configuration, including its router bean.
        return transport.getRouterFunction();
    }

    static McpTransportContext capture(ServerRequest request, McpCoreProperties core) {
        if (!HttpMethod.POST.equals(request.method())) return McpTransportContext.EMPTY;
        return McpTransportContext.create(Map.of(
                DEADLINE, RequestDeadline.create(core.getRequestTimeout(), request.headers().firstHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER)),
                METADATA, McpRequestMetadata.from(request.servletRequest())));
    }
}
