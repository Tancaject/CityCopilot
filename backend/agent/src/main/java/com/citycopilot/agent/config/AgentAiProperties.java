package com.citycopilot.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 大模型连接配置。
 *
 * <p>由 {@code citycopilot.ai} 配置项绑定，供模型客户端决定是否启用远程推理、调用哪个兼容接口以及采用何种超时边界。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.ai")
public class AgentAiProperties {
    /** 是否允许调用外部大模型；关闭时由本地启发式客户端兜底。 */
    private Boolean enabled = false;
    /** 调用模型供应商所需的密钥。 */
    private String apiKey;
    /** OpenAI 兼容的聊天补全接口地址。 */
    private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /** 请求使用的模型名称。 */
    private String model = "qwen-plus";
    /** 单次模型 HTTP 交换的超时秒数，包含完整响应正文。 */
    private Integer timeoutSeconds = 8;
}
