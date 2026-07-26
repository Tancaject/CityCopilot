package com.citycopilot.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.core-service")
public class CoreServiceProperties {
    private String baseUrl = "http://127.0.0.1:8081";
    private String serviceToken;
    private String opsServiceToken;
    private String opsReaderServiceToken;
    private String opsExecutorServiceToken;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(4);
    private Duration catalogTtl = Duration.ofSeconds(30);
}
