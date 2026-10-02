package com.citycopilot.mcp;

import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationRequest;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.http.DeadlineHttpRequestFactory;
import com.citycopilot.http.RequestDeadline;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 访问 Core 内部工具 API 的 HTTP 客户端。
 *
 * <p>它只负责传递服务身份、最终用户凭证、追踪 ID 与幂等键；授权和业务执行由 Core 完成。</p>
 */
@Component
public class McpCoreClient {
    /** Core 内部服务令牌请求头。 */
    static final String SERVICE_TOKEN_HEADER = "X-CityCopilot-Service-Token";
    /** 跨服务追踪请求头。 */
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 复用连接并应用统一基础地址与超时的同步 HTTP 客户端。 */
    private final RestClient restClient;
    /** Core 连接与服务认证配置。 */
    private final McpCoreProperties properties;

    /**
     * @param properties Core 地址、令牌及连接/读取超时
     */
    public McpCoreClient(McpCoreProperties properties) {
        this.properties = properties;
        restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(new DeadlineHttpRequestFactory(properties.getConnectTimeout(), properties.getReadTimeout(),
                        RequestDeadline::current, true, properties.getMaxConcurrent()))
                .build();
    }

    /**
     * 获取 Core 当前公开的完整工具目录。
     *
     * @param metadata 当前请求上下文
     * @return 不可变工具定义列表；空响应规范化为空列表
     */
    public List<ToolDefinition> tools(McpRequestMetadata metadata) {
        List<ToolDefinition> result = restClient.get()
                .uri("/internal/v1/tools")
                .headers(headers -> headers(headers, metadata))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return result == null ? List.of() : List.copyOf(result);
    }

    /**
     * 让 Core 根据最终用户凭证解析会话身份。
     *
     * @param metadata 当前请求上下文
     * @return 已认证身份；缺少凭证或空响应时返回匿名身份
     */
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

    /**
     * 调用 Core 中的受治理工具执行端点。
     *
     * @param toolName 稳定工具名
     * @param arguments 工具实参
     * @param metadata 当前请求上下文
     * @param expectedVersion 本次调用审阅的工具契约版本，不随 Core 最新目录变化
     * @return Core 的结构化执行结果；空响应转换为可重试失败
     */
    public ToolInvocationResponse invoke(
            String toolName,
            Map<String, Object> arguments,
            McpRequestMetadata metadata,
            int expectedVersion
    ) {
        ToolInvocationResponse response = restClient.post()
                .uri("/internal/v1/tools/{toolName}:invoke", toolName)
                .headers(headers -> {
                    headers(headers, metadata);
                    // 每次调用都发送稳定键；Core 仅在非幂等工具路径用它去重网络重试。
                    headers.set("Idempotency-Key", metadata.idempotencyKeyFor(toolName));
                })
                .body(new ToolInvocationRequest(arguments, metadata.requestId(), expectedVersion))
                .retrieve()
                .body(ToolInvocationResponse.class);
        return response == null
                ? ToolInvocationResponse.failure("empty_core_response", "Core returned an empty response", true)
                : response;
    }

    /**
     * 写入每次 Core 请求共享的服务身份、用户凭证和追踪头。
     */
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
