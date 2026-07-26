package com.citycopilot.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

record McpRequestMetadata(String authorization, String requestId, String idempotencyKey) {
    static McpRequestMetadata current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String requestId = request.getHeader(McpCoreClient.REQUEST_ID_HEADER);
            return new McpRequestMetadata(
                    request.getHeader(HttpHeaders.AUTHORIZATION),
                    requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
                    request.getHeader("Idempotency-Key")
            );
        }
        return new McpRequestMetadata(null, UUID.randomUUID().toString(), null);
    }

    String idempotencyKeyFor(String toolName) {
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? requestId + ":mcp:" + toolName
                : idempotencyKey;
    }
}
