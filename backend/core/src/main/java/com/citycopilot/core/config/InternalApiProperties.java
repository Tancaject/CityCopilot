package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.internal-api")
public class InternalApiProperties {
    private String serviceToken;
    private Map<String, String> serviceTokens = new LinkedHashMap<>();
    private Duration idempotencyTtl = Duration.ofHours(24);
}
