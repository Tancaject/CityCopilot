package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ai")
public class AiProperties {
    private Boolean enabled = false;
    private String apiKey;
    private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private String model = "qwen-plus";
    private Integer timeoutSeconds = 8;
    private Integer maxMessageLength = 300;
    private Integer publicRateLimitPerMinute = 20;
    private Integer userRateLimitPerMinute = 40;
    private Integer adminRateLimitPerMinute = 80;
    private Integer historyLimit = 6;
}
