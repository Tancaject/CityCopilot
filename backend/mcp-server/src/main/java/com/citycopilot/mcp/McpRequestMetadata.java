package com.citycopilot.mcp;

import com.citycopilot.http.DeadlineHttpRequestFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 一次 MCP 调用向 Core 透传的请求上下文。
 *
 * @param authorization 最终用户的 Authorization 请求头
 * @param requestId 跨 MCP 与 Core 的追踪标识
 * @param idempotencyKey 客户端显式提供的幂等键
 */
record McpRequestMetadata(String authorization, String requestId, String idempotencyKey) {
    private static final ThreadLocal<McpRequestMetadata> BOUND = new ThreadLocal<>();
    /**
     * 从当前 HTTP 请求提取元数据；非 HTTP 回调场景生成新的请求标识。
     *
     * @return 已规范化且始终包含 requestId 的元数据
     */
    static McpRequestMetadata current() {
        if (BOUND.get() != null) return BOUND.get();
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return from(attributes.getRequest());
        }
        return new McpRequestMetadata(null, UUID.randomUUID().toString(), null);
    }

    static McpRequestMetadata from(HttpServletRequest request) {
        String requestId = request.getHeader(McpCoreClient.REQUEST_ID_HEADER);
        return new McpRequestMetadata(request.getHeader(HttpHeaders.AUTHORIZATION),
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
                request.getHeader("Idempotency-Key"));
    }

    static String inheritedDeadline() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER) : null;
    }

    /** The MCP SDK can dispatch callbacks off the HTTP thread, so servlet ThreadLocals are insufficient. */
    static <T> T withMetadata(McpRequestMetadata metadata, Supplier<T> action) {
        McpRequestMetadata previous = BOUND.get();
        BOUND.set(metadata);
        try { return action.get(); }
        finally {
            if (previous == null) BOUND.remove(); else BOUND.set(previous);
        }
    }

    /**
     * 为指定工具生成稳定幂等键，优先保留客户端显式值。
     *
     * @param toolName 当前调用的稳定工具名
     * @return 发送给 Core 的幂等键
     */
    String idempotencyKeyFor(String toolName) {
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? requestId + ":mcp:" + toolName
                : idempotencyKey;
    }
}
