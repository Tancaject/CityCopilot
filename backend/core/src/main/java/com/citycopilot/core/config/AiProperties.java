package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 {@code citycopilot.ai}，配置 Core 旧版 AI 接口的模型连接与输入限制。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.ai")
public class AiProperties {
    /** 是否启用 Core 内置 AI 调用。 */
    private Boolean enabled = false;
    /** 模型提供方 API 密钥。 */
    private String apiKey;
    /** OpenAI 兼容 Chat Completions 地址。 */
    private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /** 调用的模型名。 */
    private String model = "qwen-plus";
    /** 单次请求超时，单位秒。 */
    private Integer timeoutSeconds = 8;
    /** 接受的用户消息最大字符数。 */
    private Integer maxMessageLength = 300;
}
