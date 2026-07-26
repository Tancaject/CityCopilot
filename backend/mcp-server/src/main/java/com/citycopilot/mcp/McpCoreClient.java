package com.citycopilot.mcp;

import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationRequest;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

@Component
public class McpCoreClient {
    static final String SERVICE_TOKEN_HEADER = "X-CityCopilot-Service-Token";
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RestClient restClient;
    private final McpCoreProperties properties;

    public McpCoreClient(McpCoreProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public List<ToolDefinition> tools(McpRequestMetadata metadata) {
        List<ToolDefinition> result = restClient.get()
                .uri("/internal/v1/tools")
                .headers(headers -> headers(headers, metadata))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return result == null ? List.of() : List.copyOf(result);
    }

    public ServiceUser session(McpRequestMetadata metadata) {
        if (metadata.authorization() == null || metadata.authorization().isBlank()) {
            return ServiceUser.anonymous();
        }
        ServiceUser user = restClient.get()
                .uri("/internal/v1/session")
                .headers(headers -> headers(headers, metadata))
                .retrieve()
                .body(ServiceUser.class);
        return user == null ? ServiceUser.anonymous() : user;
    }

    public ToolInvocationResponse invoke(
            String toolName,
            Map<String, Object> arguments,
            McpRequestMetadata metadata
    ) {
        ToolInvocationResponse response = restClient.post()
                .uri("/internal/v1/tools/{toolName}:invoke", toolName)
                .headers(headers -> {
                    headers(headers, metadata);
                    headers.set("Idempotency-Key", metadata.idempotencyKeyFor(toolName));
                })
                .body(new ToolInvocationRequest(arguments, metadata.requestId()))
                .retrieve()
                .body(ToolInvocationResponse.class);
        return response == null
                ? ToolInvocationResponse.failure("empty_core_response", "Core returned an empty response", true)
                : response;
    }

    private void headers(HttpHeaders headers, McpRequestMetadata metadata) {
        if (properties.getServiceToken() != null && !properties.getServiceToken().isBlank()) {
            headers.set(SERVICE_TOKEN_HEADER, properties.getServiceToken());
        }
        headers.set("X-CityCopilot-Service-Actor", "mcp-server");
        if (metadata.authorization() != null && !metadata.authorization().isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, metadata.authorization());
        }
        headers.set(REQUEST_ID_HEADER, metadata.requestId());
    }
}
