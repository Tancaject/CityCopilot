package com.citycopilot.agent.remote;

import com.citycopilot.agent.config.CoreServiceProperties;
import com.citycopilot.agent.budget.AgentBudget;
import com.citycopilot.agent.budget.BudgetHttpInterceptor;
import com.citycopilot.agent.budget.AgentBudgetProperties;
import com.citycopilot.http.DeadlineHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationRequest;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Agent 访问 Core 权威会话与工具接口的 HTTP 客户端。
 *
 * <p>统一转发用户授权、请求号、服务令牌和幂等键；Core 仍负责最终身份、权限、参数、幂等与业务校验。</p>
 */
@Component
public class CoreServiceClient {
    /** Core 内部接口识别 Agent 服务的令牌请求头。 */
    public static final String SERVICE_TOKEN_HEADER = "X-CityCopilot-Service-Token";
    /** Core 内部协议中的服务身份请求头名。 */
    public static final String SERVICE_ACTOR_HEADER = "X-CityCopilot-Service-Actor";
    /** 跨 Agent/Core 链路复用的请求号请求头。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 带统一根地址和超时的 Spring RestClient。 */
    private final RestClient restClient;
    /** 目录共享连接与截止时间，只按规划元数据预算读取完整工具 Schema。 */
    private final RestClient catalogClient;
    /** Core 地址、分角色服务令牌和超时配置。 */
    private final CoreServiceProperties properties;

    public CoreServiceClient(CoreServiceProperties properties) {
        this(properties, new AgentBudgetProperties());
    }

    @Autowired
    public CoreServiceClient(CoreServiceProperties properties, AgentBudgetProperties limits) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(new DeadlineHttpRequestFactory(properties.getConnectTimeout(), properties.getReadTimeout(),
                        AgentBudget::current, true, limits.getConcurrency()))
                .requestInterceptor(new BudgetHttpInterceptor(false, limits))
                .build();
        this.catalogClient = restClient.mutate()
                .requestInterceptors(interceptors -> {
                    interceptors.clear();
                    interceptors.add(new BudgetHttpInterceptor(BudgetHttpInterceptor.ResponseKind.TOOL_CATALOG, limits));
                })
                .build();
    }

    /**
     * 让 Core 解析用户授权并返回可信服务用户。
     *
     * @param authorization 前端 Authorization 头
     * @param requestId 链路请求号
     * @return Core 返回的用户；空响应按匿名处理
     * @throws CoreServiceCallException Core 不可达或返回错误状态
     */
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

    /**
     * 拉取 Core 权威工具目录。
     *
     * @return 工具定义不可变副本；空响应转为空列表
     */
    public List<ToolDefinition> tools(String requestId) {
        try {
            List<ToolDefinition> definitions = catalogClient.get()
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

    /**
     * 调用 Core 中的版本化工具端点。
     *
     * @param toolName 工具注册名
     * @param arguments 结构化参数
     * @param context 用户授权、请求号、服务身份和幂等键
     * @return Core 工具响应；空 HTTP 正文转换为可重试失败
     */
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
                        // 幂等键必须原样跨服务传递，Core 才能对写工具实施最终去重。
                        if (context != null && context.idempotencyKey() != null && !context.idempotencyKey().isBlank()) {
                            headers.set("Idempotency-Key", context.idempotencyKey());
                        }
                    })
                    .body(new ToolInvocationRequest(arguments, context == null ? null : context.requestId(),
                            context == null ? null : context.expectedVersion()))
                    .retrieve()
                    .body(ToolInvocationResponse.class);
            return response == null
                    ? ToolInvocationResponse.failure("empty_core_response", "Core returned an empty response", true)
                    : response;
        } catch (Exception error) {
            throw translate(error);
        }
    }

    /**
     * 按普通 Agent、旧 Ops、只读 Ops 和执行 Ops 选择最小权限服务令牌，并转发用户链路字段。
     */
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

    /**
     * 仅识别无用户 Authorization 的固定内部 Ops 身份，普通 userId 不能选择高权限服务令牌。
     */
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

    /** 将 HTTP 状态和连接异常归一为稳定错误码及保守重试语义。 */
    private CoreServiceCallException translate(Exception error) {
        AgentBudget.rethrowIfStopped(error);
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
