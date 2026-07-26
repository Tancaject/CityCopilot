package com.citycopilot.agent.config;

import com.citycopilot.agent.security.CoreSessionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AgentWebConfig implements WebMvcConfigurer {
    private final CoreSessionInterceptor coreSessionInterceptor;

    public AgentWebConfig(CoreSessionInterceptor coreSessionInterceptor) {
        this.coreSessionInterceptor = coreSessionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(coreSessionInterceptor)
                .addPathPatterns("/agent/**")
                .excludePathPatterns("/agent/ping");
    }
}
