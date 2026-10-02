package com.citycopilot.agent.config;

import com.citycopilot.agent.security.CoreSessionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Agent Web 鉴权拦截配置。
 *
 * <p>将 Core 会话解析拦截器应用到所有 Agent 接口，仅放行用于存活检查的 ping 端点。</p>
 */
@Configuration
public class AgentWebConfig implements WebMvcConfigurer {
    /** 将可信 Core 会话转换为当前请求安全上下文的拦截器。 */
    private final CoreSessionInterceptor coreSessionInterceptor;

    public AgentWebConfig(CoreSessionInterceptor coreSessionInterceptor) {
        this.coreSessionInterceptor = coreSessionInterceptor;
    }

    /** 注册 Agent 路由的统一会话校验范围。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(coreSessionInterceptor)
                .addPathPatterns("/agent/**")
                .excludePathPatterns("/agent/ping");
    }
}
