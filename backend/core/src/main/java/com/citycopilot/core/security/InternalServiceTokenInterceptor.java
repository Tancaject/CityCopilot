package com.citycopilot.core.security;

import com.citycopilot.core.config.InternalApiProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public final class InternalServiceTokenInterceptor implements HandlerInterceptor {
    public static final String SERVICE_TOKEN_HEADER = "X-CityCopilot-Service-Token";
    public static final String SERVICE_ACTOR_HEADER = "X-CityCopilot-Service-Actor";
    public static final String AUTHENTICATED_ACTOR_ATTRIBUTE = "citycopilot.authenticatedServiceActor";

    private final InternalApiProperties properties;

    public InternalServiceTokenInterceptor(InternalApiProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Map<String, String> credentials = properties.getServiceTokens() == null
                ? Map.of()
                : properties.getServiceTokens();
        boolean namedCredentialsConfigured = credentials.values().stream()
                .anyMatch(token -> token != null && !token.isBlank());
        String legacyToken = properties.getServiceToken();
        if (!namedCredentialsConfigured && (legacyToken == null || legacyToken.isBlank())) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_API_DISABLED", "内部服务接口未配置");
            return false;
        }
        String provided = request.getHeader(SERVICE_TOKEN_HEADER);
        String actor = namedCredentialsConfigured
                ? authenticatedActor(credentials, provided)
                : provided != null && constantTimeEquals(legacyToken, provided) ? "legacy-service" : null;
        if (actor == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, "SERVICE_AUTH_REQUIRED", "内部服务认证失败");
            return false;
        }
        request.setAttribute(AUTHENTICATED_ACTOR_ATTRIBUTE, actor);
        return true;
    }

    private String authenticatedActor(Map<String, String> credentials, String provided) {
        if (provided == null || provided.isBlank()) {
            return null;
        }
        String matchedActor = null;
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            String configured = entry.getValue();
            if (configured == null || configured.isBlank() || !constantTimeEquals(configured, provided)) {
                continue;
            }
            if (matchedActor != null) {
                return null;
            }
            matchedActor = entry.getKey();
        }
        return matchedActor;
    }

    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message) throws Exception {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
