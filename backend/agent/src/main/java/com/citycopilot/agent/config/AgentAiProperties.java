package com.citycopilot.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.ai")
public class AgentAiProperties {
    private Boolean enabled = false;
    private String apiKey;
    private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private String model = "qwen-plus";
    private Integer timeoutSeconds = 8;
    private Integer maxMessageLength = 300;
}
