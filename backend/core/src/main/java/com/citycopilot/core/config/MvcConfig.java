package com.citycopilot.core.config;

import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.utils.AdminInterceptor;
import com.citycopilot.core.utils.LoginInterceptor;
import com.citycopilot.core.utils.RefreshTokenInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UploadProperties uploadProperties;
    @Resource
    private PermissionPolicy permissionPolicy;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String uploadPublicPattern = normalizePublicPrefix(uploadProperties.getPublicPrefix()) + "**";
        // 第一个拦截器：刷新 token，拦截所有请求
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0); // 优先级最高，先执行

        // 第二个拦截器：管理员校验，保护运营写接口
        registry.addInterceptor(new AdminInterceptor(permissionPolicy))
                .addPathPatterns(
                        "/admin/**",
                        "/agent/traces",
                        "/agent/traces/**",
                        "/geo/**",
                        "/shop",
                        "/shop/*/geo",
                        "/voucher",
                        "/voucher/seckill",
                        "/voucher-order/*/use"
                )
                .order(1);

        // 第三个拦截器：登录校验，只拦截需要登录的路径
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/blog/hot",
                        "/agent/ping",
                        "/agent/chat",
                        "/mcp/tools/list",
                        "/actuator/**",
                        "/health",
                        "/user/code",
                        "/user/login",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/imgs/**",
                        uploadPublicPattern
                )
                .order(2); // 后执行
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String publicPrefix = normalizePublicPrefix(uploadProperties.getPublicPrefix());
        Path root = Path.of(uploadProperties.getRootDir()).toAbsolutePath().normalize();
        registry.addResourceHandler(publicPrefix + "**")
                .addResourceLocations(root.toUri().toString());
    }

    private String normalizePublicPrefix(String prefix) {
        String normalized = prefix == null || prefix.isBlank() ? "/uploads" : prefix.replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
