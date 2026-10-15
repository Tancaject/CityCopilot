package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 {@code citycopilot.kafka}：跨服务领域事件的主题、消费组和开关；连接参数走 {@code spring.kafka}。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.kafka")
public class KafkaEventProperties {
    /** 是否启用 Kafka 事件消费。 */
    private boolean enabled;
    /** 是否在当前 Core 实例启动消费者。 */
    private boolean consumerEnabled = true;
    /** Commerce 领域事件主题。 */
    private String commerceTopic = "citycopilot-commerce-events-v1";
    /** 运营与对账事件主题。 */
    private String opsTopic = "citycopilot-ops-events-v1";
    /** Core 审计消费者组。 */
    private String auditConsumerGroup = "citycopilot-event-audit-v1";
    /** 店铺销量投影消费者组；与审计组独立提交位点、独立 Inbox 水位。 */
    private String shopSalesConsumerGroup = "citycopilot-shop-sales-v1";
}
