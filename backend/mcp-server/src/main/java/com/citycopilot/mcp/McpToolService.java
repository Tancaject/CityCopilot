package com.citycopilot.mcp;

import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.http.RequestDeadline;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * MCP 工具目录与调用的应用服务。
 *
 * <p>目录先按 MCP audience 过滤，HTTP 列表还会结合 Core 会话权限；实际调用统一转发给
 * Core 做最终授权、参数转换和业务执行。</p>
 */
@Service
public class McpToolService {
    /** 获取权威工具目录、身份并执行工具的 Core 客户端。 */
    private final McpCoreClient coreClient;
    private final McpCoreProperties properties;
    private final Semaphore capacity;

    public McpToolService(McpCoreClient coreClient, McpCoreProperties properties) {
        this.coreClient = coreClient;
        this.properties = properties;
        this.capacity = new Semaphore(properties.getMaxConcurrent());
    }

    /**
     * @return audience 允许 MCP 且当前用户具备全部所需权限的工具
     */
    public List<ToolDefinition> availableTools() {
        return operation(() -> {
            McpRequestMetadata metadata = McpRequestMetadata.current();
            ServiceUser user = coreClient.session(metadata);
            RequestDeadline.current().check();
            return catalog(metadata).stream()
                    .filter(this::availableToMcp)
                    .filter(tool -> tool.requiredPermissions().isEmpty()
                            || tool.requiredPermissions().stream().allMatch(user::hasPermission))
                    .toList();
        });
    }

    /**
     * 获取未按最终用户权限过滤、但 audience 允许 MCP 的工具目录。
     *
     * @return MCP 协议层可暴露的工具定义
     */
    public List<ToolDefinition> catalog() {
        return operation(() -> catalog(McpRequestMetadata.current()).stream()
                .filter(this::availableToMcp).toList());
    }

    /** @return 已转换为 MCP input/output Schema 的当前用户工具列表 */
    public List<Map<String, Object>> toolsList() {
        return McpToolMapper.toToolsList(availableTools());
    }

    /**
     * 校验工具属于 MCP audience 后转发给 Core；Core 会再次执行最终授权与参数校验。
     *
     * @param request 工具调用载荷
     * @return 结构化工具结果
     */
    public ToolInvocationResponse call(McpToolCallRequest request) {
        return operation(() -> callWithinDeadline(request));
    }

    private ToolInvocationResponse callWithinDeadline(McpToolCallRequest request) {
        McpRequestMetadata metadata = McpRequestMetadata.current();
        List<ToolDefinition> definitions = catalog(metadata).stream().filter(this::availableToMcp).toList();
        RequestDeadline.current().check();
        // 目录非空时拒绝不在 MCP audience 中的工具；最终安全边界仍是 Core 的执行入口。
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
                metadata,
                request.expectedVersion() == null ? 1 : request.expectedVersion()
        );
    }

    private <T> T operation(Supplier<T> action) {
        if (!capacity.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MCP processing capacity is full; this operation has not started");
        }
        try (RequestDeadline.Scope ignored = RequestDeadline.open(properties.getRequestTimeout(), McpRequestMetadata.inheritedDeadline())) {
            RequestDeadline.current().check();
            T result = action.get();
            RequestDeadline.current().check();
            return result;
        } catch (RuntimeException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof RequestDeadline.DeadlineExceededException || cause instanceof SocketTimeoutException
                        || cause instanceof HttpTimeoutException) {
                    throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                            "MCP Core request exceeded its deadline. A submitted write may already be committed; its outcome is unknown. "
                                    + "Query the business state before retrying with the original idempotency key", error);
                }
            }
            throw error;
        } finally {
            capacity.release();
        }
    }

    /** @return 指定请求上下文下的 Core 权威工具目录 */
    private List<ToolDefinition> catalog(McpRequestMetadata metadata) {
        return coreClient.tools(metadata);
    }

    /** @return 工具是否允许通过 MCP 被发现和调用 */
    private boolean availableToMcp(ToolDefinition tool) {
        return tool.audience() == ToolAudience.ALL || tool.audience() == ToolAudience.MCP;
    }
}
