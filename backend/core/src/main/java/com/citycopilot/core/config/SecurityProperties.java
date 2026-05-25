package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.security")
public class SecurityProperties {
    private Map<String, Set<String>> rolePermissions = new LinkedHashMap<>();
    private Integer loginCodeRateLimitPerPhone = 3;
}
