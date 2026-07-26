package com.citycopilot.agent.ops.v2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.rocketmq")
public class AgentRocketMqProperties {
    private boolean enabled;
    private String endpoints = "127.0.0.1:18081";
    private String opsTopic = "citycopilot-ops-events-v1";
    private String consumerGroup = "citycopilot-ops-agent-v2";
    private Duration requestTimeout = Duration.ofSeconds(3);
}
