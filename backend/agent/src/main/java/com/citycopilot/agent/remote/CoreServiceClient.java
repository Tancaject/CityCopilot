package com.citycopilot.agent.remote;

import com.citycopilot.agent.config.CoreServiceProperties;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationRequest;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

@Component
public class CoreServiceClient {
    public static final String SERVICE_TOKEN_HEADER = "X-CityCopilot-Service-Token";
    public static final String SERVICE_ACTOR_HEADER = "X-CityCopilot-Service-Actor";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RestClient restClient;
    private final CoreServiceProperties properties;

    public CoreServiceClient(CoreServiceProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public ServiceUser resolveSession(String authorization, String requestId) {
        try {
            ServiceUser user = restClient.get()
                    .uri("/internal/v1/session")
                    .headers(headers -> commonHeaders(headers, authorization, requestId, null))
                    .retrieve()
                    .body(ServiceUser.class);
            return user == null ? ServiceUser.anonymous() : user;
        } catch (Exception error) {
            throw translate(error);
        }
    }

    public List<ToolDefinition> tools(String requestId) {
        try {
            List<ToolDefinition> definitions = restClient.get()
                    .uri("/internal/v1/tools")
                    .headers(headers -> commonHeaders(headers, null, requestId, null))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return definitions == null ? List.of() : List.copyOf(definitions);
        } catch (Exception error) {
            throw translate(error);
        }
    }

    public ToolInvocationResponse invoke(String toolName, Map<String, Object> arguments, ToolCallContext context) {
        try {
            ToolInvocationResponse response = restClient.post()
                    .uri("/internal/v1/tools/{toolName}:invoke", toolName)
                    .headers(headers -> {
                        commonHeaders(
                                headers,
                                context == null ? null : context.authorization(),
                                context == null ? null : context.requestId(),
                                serviceActor(context)
                        );
                        if (context != null && context.idempotencyKey() != null && !context.idempotencyKey().isBlank()) {
                            headers.set("Idempotency-Key", context.idempotencyKey());
                        }
                    })
                    .body(new ToolInvocationRequest(arguments, context == null ? null : context.requestId()))
                    .retrieve()
                    .body(ToolInvocationResponse.class);
            return response == null
                    ? ToolInvocationResponse.failure("empty_core_response", "Core returned an empty response", true)
                    : response;
        } catch (Exception error) {
            throw translate(error);
        }
    }

    private void commonHeaders(
            HttpHeaders headers,
            String authorization,
            String requestId,
            String serviceActor
    ) {
        String serviceToken = switch (serviceActor == null ? "" : serviceActor) {
            case "ops-agent" -> properties.getOpsServiceToken();
            case "ops-reader" -> properties.getOpsReaderServiceToken();
            case "ops-executor" -> properties.getOpsExecutorServiceToken();
            default -> properties.getServiceToken();
        };
        if (serviceToken != null && !serviceToken.isBlank()) {
            headers.set(SERVICE_TOKEN_HEADER, serviceToken);
        }
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (requestId != null && !requestId.isBlank()) {
            headers.set(REQUEST_ID_HEADER, requestId);
        }
    }

    private String serviceActor(ToolCallContext context) {
        if (context == null || context.authorization() != null) {
            return null;
        }
        String userId = context.userId();
        if (userId == null) {
            return null;
        }
        return switch (userId) {
            case "ops-agent", "ops-reader", "ops-executor" -> context.userId();
            default -> null;
        };
    }

    private CoreServiceCallException translate(Exception error) {
        if (error instanceof CoreServiceCallException known) {
            return known;
        }
        if (error instanceof RestClientResponseException responseError) {
            HttpStatusCode status = responseError.getStatusCode();
            return new CoreServiceCallException(
                    "core_http_" + status.value(),
                    "Core service returned HTTP " + status.value(),
                    status.is5xxServerError(),
                    error
            );
        }
        return new CoreServiceCallException(
                "core_unavailable",
                error.getMessage() == null ? "Core service unavailable" : error.getMessage(),
                true,
                error
        );
    }
}
