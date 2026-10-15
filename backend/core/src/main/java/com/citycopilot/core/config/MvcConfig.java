package com.citycopilot.core.config;

import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.security.InternalServiceTokenFilter;
import com.citycopilot.core.utils.AdminInterceptor;
import com.citycopilot.core.utils.LoginInterceptor;
import com.citycopilot.core.utils.RefreshTokenInterceptor;
import com.citycopilot.core.utils.UserContextFilter;
import com.citycopilot.core.utils.UserRateLimitInterceptor;
import com.citycopilot.http.DeadlineHandlerInterceptor;
import com.citycopilot.http.RequestIdFilter;
import org.springframework.core.Ordered;
import jakarta.annotation.Resource;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Core 的 MVC 拦截器链和上传文件静态资源映射。
 *
 * <p>Servlet Filter 认证服务并限定身份作用域；MVC 在资源准入后恢复会话，再执行管理权限与登录校验。</p>
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    /** 恢复登录会话和刷新 TTL 使用的 Redis 客户端。 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /** 上传目录与公开 URL 配置。 */
    @Resource
    private UploadProperties uploadProperties;
    /** 管理端角色权限判断策略。 */
    @Resource
    private PermissionPolicy permissionPolicy;
    /** 内部 API 服务令牌配置。 */
    @Resource
    private InternalApiProperties internalApiProperties;
    @Value("${citycopilot.http.request-timeout:8s}")
    private Duration requestTimeout;
    @Value("${citycopilot.http.max-concurrent:128}")
    private int maxConcurrent;
    @Value("${citycopilot.http.authority-max-concurrent:32}")
    private int authorityMaxConcurrent;

    /** 最先执行，让后续过滤器和业务日志都带上同一个请求 ID。 */
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestId() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<InternalServiceTokenFilter> internalServiceAuthentication() {
        var registration = new FilterRegistrationBean<>(new InternalServiceTokenFilter(internalApiProperties));
        registration.setOrder(-100);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(java.util.EnumSet.allOf(DispatcherType.class));
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<UserContextFilter> sessionUserContext() {
        var registration = new FilterRegistrationBean<>(new UserContextFilter());
        registration.setOrder(-90);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(java.util.EnumSet.allOf(DispatcherType.class));
        registration.setAsyncSupported(true);
        return registration;
    }

    /** 方法权限依赖 HandlerMethod；保留原路径范围与公开白名单。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String uploadPublicPattern = normalizePublicPrefix(uploadProperties.getPublicPrefix()) + "**";
        registry.addInterceptor(new DeadlineHandlerInterceptor(requestTimeout, maxConcurrent,
                        request -> request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE) != null))
                .addPathPatterns("/**")
                .excludePathPatterns("/internal/v1/place/**", "/actuator/**", "/health", uploadPublicPattern)
                .order(0);
        // Commerce may call back while the original Core request holds its admission slot.
        registry.addInterceptor(new DeadlineHandlerInterceptor(requestTimeout, authorityMaxConcurrent,
                        request -> request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE) != null))
                .addPathPatterns("/internal/v1/place/**").order(0);
        // 会话 Redis 访问沿用同一请求预算，不在 Servlet Filter 中提前执行。
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
        // 按 @RequirePermission 注解执行细粒度权限判断；/admin 下无注解时默认要求 admin:access。
        registry.addInterceptor(new AdminInterceptor(permissionPolicy))
                .addPathPatterns("/**")
                .order(1);

        // 最后为除公开白名单外的接口统一要求登录。
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/blog/hot",
                        "/actuator/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/health",
                        "/internal/**",
                        "/user/code",
                        "/user/login",
                        "/booking-rules",
                        "/shops/*/booking-resources",
                        "/shops/*/booking-coupon-rules",
                        "/booking-resources/*/slots",
                        "/booking-resources/*/offers",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        uploadPublicPattern
                )
                .order(2);
        registry.addInterceptor(new UserRateLimitInterceptor(stringRedisTemplate))
                .addPathPatterns("/voucher-order/seckill/*").order(3);
    }

    /** 把配置的上传目录以只读静态资源方式映射到公开 URL 前缀。 */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String publicPrefix = normalizePublicPrefix(uploadProperties.getPublicPrefix());
        Path root = Path.of(uploadProperties.getRootDir()).toAbsolutePath().normalize();
        registry.addResourceHandler(publicPrefix + "**")
                .addResourceLocations(root.toUri().toString());
    }

    /**
     * @param prefix 配置的公开 URL 前缀
     * @return 始终以斜杠开头和结尾的规范前缀
     */
    private String normalizePublicPrefix(String prefix) {
        String normalized = prefix == null || prefix.isBlank() ? "/uploads" : prefix.replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
