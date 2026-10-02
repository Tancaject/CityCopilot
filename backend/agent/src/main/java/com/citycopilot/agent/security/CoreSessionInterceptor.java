package com.citycopilot.agent.security;

import com.citycopilot.agent.remote.CoreServiceClient;
import com.citycopilot.contract.security.ServiceUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * 在进入 Agent 控制器前向 Core 验证用户会话的 MVC 拦截器。
 *
 * <p>生成或沿用 requestId，将可信用户、原始授权头与请求号放入线程上下文，并在响应完成后无条件清理。</p>
 */
@Component
public final class CoreSessionInterceptor implements HandlerInterceptor {
    /** 调用 Core 会话解析接口的客户端。 */
    private final CoreServiceClient coreServiceClient;

    public CoreSessionInterceptor(CoreServiceClient coreServiceClient) {
        this.coreServiceClient = coreServiceClient;
    }

    /**
     * 解析请求身份并建立上下文；没有 Authorization 时按匿名处理而不外调 Core。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String requestId = request.getHeader(CoreServiceClient.REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        // 用户权限始终由 Core 会话接口产生，Agent 不解析或信任令牌内自报字段。
        ServiceUser user = authorization == null || authorization.isBlank()
                ? ServiceUser.anonymous()
                : coreServiceClient.resolveSession(authorization, requestId);
        AgentRequestContextHolder.set(new AgentRequestContext(user, authorization, requestId));
        response.setHeader(CoreServiceClient.REQUEST_ID_HEADER, requestId);
        return true;
    }

    /** 请求结束后移除线程本地身份，即使控制器抛出异常也会执行。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AgentRequestContextHolder.clear();
    }
}
