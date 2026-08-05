package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.rocketmq")
public class RocketMqProperties {
    private boolean enabled;
    private boolean consumerEnabled = true;
    private boolean sslEnabled;
    private String accessKey = "";
    private String secretKey = "";
    private String endpoints = "127.0.0.1:8081";
    private String commerceTopic = "citycopilot-commerce-events-v1";
    private String opsTopic = "citycopilot-ops-events-v1";
    private String auditConsumerGroup = "citycopilot-event-audit-v1";
    private Duration requestTimeout = Duration.ofSeconds(3);
}
