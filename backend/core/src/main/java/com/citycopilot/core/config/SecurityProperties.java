package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 绑定 {@code citycopilot.security}，允许部署环境覆盖角色权限与验证码限流。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.security")
public class SecurityProperties {
    /** 角色到权限码集合的配置覆盖。 */
    private Map<String, Set<String>> rolePermissions = new LinkedHashMap<>();
    /** 单手机号在限流窗口内允许发送的验证码次数。 */
    private Integer loginCodeRateLimitPerPhone = 3;
}
