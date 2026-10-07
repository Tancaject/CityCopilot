package com.citycopilot.commerce;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 在 Servlet 分派进入 MVC 或其他处理器前保护 Commerce 业务路径。
 *
 * <p>令牌未配置时同样拒绝业务请求，防止因漏配而意外开放服务。</p>
 */
public class CommerceSecurityFilter extends HttpFilter {
    private static final PathPattern COMMERCE = PathPatternParser.defaultInstance.parse("/commerce/**");
    /** 配置中的期望令牌，以字节保存供恒定时间比较。 */
    private final byte[] expected;
    private final byte[] previous;

    public CommerceSecurityFilter(String token, String previousToken) {
        expected = token == null || token.isBlank() ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        previous = previousToken == null || previousToken.isBlank() ? new byte[0]
                : previousToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String uri = (String) request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
        String context = uri == null ? request.getContextPath()
                : (String) request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH);
        if (uri == null) uri = request.getRequestURI();
        // 原始命名空间不能因其他 Servlet 映射被剥离，MVC 前缀映射也不能绕过鉴权。
        boolean protectedPath = COMMERCE.matches(RequestPath.parse(uri, context == null ? "" : context).pathWithinApplication())
                || COMMERCE.matches(ServletRequestPathUtils.parse(request).pathWithinApplication());
        if (!protectedPath
                || authenticate(request, response)) {
            chain.doFilter(request, response);
        } else {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("""
                    {"success":false,"code":"COMMERCE_SERVICE_AUTH_REQUIRED","message":"Commerce service authentication failed; ask the service operator to verify the caller and receiver credential versions before retrying"}
                    """);
        }
    }

    /** 当前令牌缺失时拒绝业务请求；额外令牌仅用于轮换重叠窗口。 */
    boolean authenticate(HttpServletRequest request, HttpServletResponse response) {
        String token = request.getHeader("X-CityCopilot-Service-Token");
        byte[] supplied = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        // MessageDigest.isEqual 避免普通字符串比较暴露与前缀匹配相关的时间差。
        boolean currentMatches = MessageDigest.isEqual(expected, supplied);
        boolean previousMatches = previous.length > 0 && MessageDigest.isEqual(previous, supplied);
        if (expected.length == 0 || (!currentMatches && !previousMatches)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
