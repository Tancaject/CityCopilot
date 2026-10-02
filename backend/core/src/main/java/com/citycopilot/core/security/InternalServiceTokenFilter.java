package com.citycopilot.core.security;

import com.citycopilot.core.config.InternalApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 在 Servlet 分派进入 MVC 或其他处理器前保护 {@code /internal/**}。
 *
 * <p>优先使用“服务名 -> 令牌”配置识别真实调用方，并用常量时间比较降低令牌侧信道风险；
 * 未配置任何令牌时直接关闭内部接口。</p>
 */
public final class InternalServiceTokenFilter extends HttpFilter {
    private static final PathPattern INTERNAL = PathPatternParser.defaultInstance.parse("/internal/**");
    /** 内部调用提交服务令牌的请求头。 */
    public static final String SERVICE_TOKEN_HEADER = "X-CityCopilot-Service-Token";
    /** 兼容客户端发送的服务名提示头；认证身份仍以令牌映射为准。 */
    public static final String SERVICE_ACTOR_HEADER = "X-CityCopilot-Service-Actor";
    /** 认证成功后写入 request、供控制器读取的服务身份属性。 */
    public static final String AUTHENTICATED_ACTOR_ATTRIBUTE = "citycopilot.authenticatedServiceActor";

    /** 内部 API 令牌配置。 */
    private final InternalApiProperties properties;

    /** @param properties 命名服务令牌及旧版单令牌配置 */
    public InternalServiceTokenFilter(InternalApiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // INCLUDE 保留原 requestURI，必须按实际包含目标检查；其他分派的 URI 已指向目标。
        String uri = (String) request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
        String context = uri == null ? request.getContextPath()
                : (String) request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH);
        if (uri == null) uri = request.getRequestURI();
        // 同时覆盖应用内命名空间和 DispatcherServlet 使用前缀映射时的 MVC 路径。
        if (!INTERNAL.matches(RequestPath.parse(uri, context == null ? "" : context).pathWithinApplication())
                && !INTERNAL.matches(ServletRequestPathUtils.parse(request).pathWithinApplication())) {
            chain.doFilter(request, response);
            return;
        }
        Object previousActor = request.getAttribute(AUTHENTICATED_ACTOR_ATTRIBUTE);
        request.removeAttribute(AUTHENTICATED_ACTOR_ATTRIBUTE);
        try {
            if (authenticate(request, response)) chain.doFilter(request, response);
        } finally {
            if (previousActor == null) request.removeAttribute(AUTHENTICATED_ACTOR_ATTRIBUTE);
            else request.setAttribute(AUTHENTICATED_ACTOR_ATTRIBUTE, previousActor);
        }
    }

    /**
     * 在进入控制器前认证服务令牌并写入可信 actor。
     *
     * @return 认证成功时为 {@code true}；失败时已写入 JSON 错误响应
     */
    boolean authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> credentials = properties.getServiceTokens() == null
                ? Map.of()
                : properties.getServiceTokens();
        Map<String, String> previousCredentials = properties.getPreviousServiceTokens() == null
                ? Map.of()
                : properties.getPreviousServiceTokens();
        boolean namedCredentialsConfigured = credentials.values().stream()
                .anyMatch(token -> token != null && !token.isBlank())
                || previousCredentials.values().stream().anyMatch(token -> token != null && !token.isBlank());
        String legacyToken = properties.getServiceToken();
        if (!namedCredentialsConfigured && (legacyToken == null || legacyToken.isBlank())) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL_API_DISABLED",
                    "Core 内部服务凭据未配置；请配置 citycopilot.internal-api.service-tokens 并重新发布接收实例");
            return false;
        }
        String provided = request.getHeader(SERVICE_TOKEN_HEADER);
        String actor = namedCredentialsConfigured
                ? authenticatedActor(credentials, previousCredentials, provided)
                : provided != null && constantTimeEquals(legacyToken, provided) ? "legacy-service" : null;
        if (actor == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, "SERVICE_AUTH_REQUIRED",
                    "Core 内部服务认证失败；请核对调用方与接收实例的凭据版本及 actor 绑定，修正后再重试");
            return false;
        }
        request.setAttribute(AUTHENTICATED_ACTOR_ATTRIBUTE, actor);
        return true;
    }

    /**
     * 在命名凭据中寻找唯一匹配项，重复令牌会被拒绝以避免身份歧义。
     *
     * @param credentials 服务名到令牌的配置
     * @param provided 请求提供的令牌
     * @return 唯一匹配的服务名；没有或重复匹配时为 {@code null}
     */
    private String authenticatedActor(Map<String, String> credentials, Map<String, String> previousCredentials,
                                      String provided) {
        if (provided == null || provided.isBlank()) {
            return null;
        }
        String matchedActor = null;
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            String configured = entry.getValue();
            // 清空当前凭据即撤销 actor，遗留的 previous 不能重新启用它。
            if (configured == null || configured.isBlank()) {
                continue;
            }
            String previous = previousCredentials.get(entry.getKey());
            boolean currentMatches = constantTimeEquals(configured, provided);
            boolean previousMatches = previous != null && !previous.isBlank() && constantTimeEquals(previous, provided);
            if (!currentMatches && !previousMatches) {
                continue;
            }
            if (matchedActor != null) {
                return null;
            }
            matchedActor = entry.getKey();
        }
        return matchedActor;
    }

    /** @return 两个令牌的 UTF-8 字节是否以常量时间比较相等 */
    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    /** 向内部调用方写入统一 JSON 错误，不再继续 MVC 调用链。 */
    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
