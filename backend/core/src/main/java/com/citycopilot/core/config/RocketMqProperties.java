package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 绑定 {@code citycopilot.rocketmq}，配置跨服务领域事件的 RocketMQ 客户端。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.rocketmq")
public class RocketMqProperties {
    /** 是否启用 RocketMQ 能力。 */
    private boolean enabled;
    /** 是否在当前 Core 实例启动消费者。 */
    private boolean consumerEnabled = true;
    /** 是否使用 TLS 连接。 */
    private boolean sslEnabled;
    /** 可选访问密钥。 */
    private String accessKey = "";
    /** 可选密钥 Secret。 */
    private String secretKey = "";
    /** RocketMQ Proxy/Endpoint 地址。 */
    private String endpoints = "127.0.0.1:8081";
    /** Commerce 领域事件主题。 */
    private String commerceTopic = "citycopilot-commerce-events-v1";
    /** 运营与对账事件主题。 */
    private String opsTopic = "citycopilot-ops-events-v1";
    /** Core 审计消费者组。 */
    private String auditConsumerGroup = "citycopilot-event-audit-v1";
    /** 单次客户端请求超时。 */
    private Duration requestTimeout = Duration.ofSeconds(3);
}
