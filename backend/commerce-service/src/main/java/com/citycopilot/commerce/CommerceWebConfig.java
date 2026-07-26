package com.citycopilot.commerce;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CommerceWebConfig implements WebMvcConfigurer {
    private final CommerceSecurityInterceptor interceptor;

    public CommerceWebConfig(CommerceSecurityInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/commerce/**");
    }
}
