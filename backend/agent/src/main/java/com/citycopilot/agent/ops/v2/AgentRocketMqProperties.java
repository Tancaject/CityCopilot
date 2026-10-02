package com.citycopilot.agent.ops.v2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** RocketMQ 运维事件消费者的连接与订阅配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.rocketmq")
public class AgentRocketMqProperties {
    /** 是否启用 RocketMQ 基础设施。 */
    private boolean enabled;
    /** 是否创建 Agent 侧事件消费者。 */
    private boolean consumerEnabled = true;
    /** 是否对 RocketMQ 连接启用 TLS。 */
    private boolean sslEnabled;
    /** 可选访问密钥，必须与 secretKey 成对配置。 */
    private String accessKey = "";
    /** 可选访问密钥密码。 */
    private String secretKey = "";
    /** RocketMQ Proxy/gRPC 端点。 */
    private String endpoints = "127.0.0.1:18081";
    /** Core 发布库存差异事件的主题。 */
    private String opsTopic = "citycopilot-ops-events-v1";
    /** Agent v2 事件消费组。 */
    private String consumerGroup = "citycopilot-ops-agent-v2";
    /** 客户端控制面请求超时。 */
    private Duration requestTimeout = Duration.ofSeconds(3);
}
