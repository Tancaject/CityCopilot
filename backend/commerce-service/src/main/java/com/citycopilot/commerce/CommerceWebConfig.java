package com.citycopilot.commerce;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import com.citycopilot.http.DeadlineHandlerInterceptor;
import java.time.Duration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Servlet Filter 认证 Commerce 路径，MVC 拦截器分配请求资源预算。 */
@Configuration
public class CommerceWebConfig implements WebMvcConfigurer {
    private final DeadlineHandlerInterceptor deadline;

    public CommerceWebConfig(
            @Value("${citycopilot.http.request-timeout:8s}") Duration requestTimeout,
            @Value("${citycopilot.http.max-concurrent:128}") int maxConcurrent) {
        this.deadline = new DeadlineHandlerInterceptor(requestTimeout, maxConcurrent, request -> true);
    }

    @Bean
    FilterRegistrationBean<CommerceSecurityFilter> commerceAuthentication(
            @Value("${citycopilot.commerce.service-token:}") String token,
            @Value("${citycopilot.commerce.previous-service-token:}") String previousToken) {
        var registration = new FilterRegistrationBean<>(new CommerceSecurityFilter(token, previousToken));
        registration.setOrder(-100);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(java.util.EnumSet.allOf(DispatcherType.class));
        registration.setAsyncSupported(true);
        return registration;
    }

    /** 仅拦截 Commerce 业务路径；Actuator 等端点不经过该路径规则。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deadline).addPathPatterns("/commerce/**", "/payment-callbacks/**").order(0);
    }
}
