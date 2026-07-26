package com.citycopilot.commerce;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class CommerceSecurityInterceptor implements HandlerInterceptor {
    private final byte[] expected;

    public CommerceSecurityInterceptor(
            @Value("${citycopilot.commerce.service-token:}") String token
    ) {
        expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getRequestURI().startsWith("/actuator/")
                || "/health".equals(request.getRequestURI())) {
            return true;
        }
        String token = request.getHeader("X-CityCopilot-Service-Token");
        byte[] supplied = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (expected.length == 0 || !MessageDigest.isEqual(expected, supplied)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
