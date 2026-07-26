package com.citycopilot.agent.security;

import com.citycopilot.agent.remote.CoreServiceClient;
import com.citycopilot.contract.security.ServiceUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public final class CoreSessionInterceptor implements HandlerInterceptor {
    private final CoreServiceClient coreServiceClient;

    public CoreSessionInterceptor(CoreServiceClient coreServiceClient) {
        this.coreServiceClient = coreServiceClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String requestId = request.getHeader(CoreServiceClient.REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ServiceUser user = authorization == null || authorization.isBlank()
                ? ServiceUser.anonymous()
                : coreServiceClient.resolveSession(authorization, requestId);
        AgentRequestContextHolder.set(new AgentRequestContext(user, authorization, requestId));
        response.setHeader(CoreServiceClient.REQUEST_ID_HEADER, requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AgentRequestContextHolder.clear();
    }
}
