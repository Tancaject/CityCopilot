package com.citycopilot.core.event;

import com.citycopilot.core.config.RocketMqProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * 订阅 Commerce 与 Ops 领域事件的 Core 审计消费者。
 *
 * <p>它校验统一事件信封，通过 {@link InboxRepository} 去重/判序并记录指标；解析、校验或存储异常返回 FAILURE，
 * 交由 RocketMQ 重投，重复、过期或已隔离事件则安全确认。</p>
 */
@Component
public class RocketMqEventAuditConsumer {
    /** RocketMQ 连接、主题和消费组配置。 */
    private final RocketMqProperties properties;
    /** 事件去重、冲突隔离和版本水位仓库。 */
    private final InboxRepository inboxRepository;
    /** 解析统一 JSON 事件信封。 */
    private final ObjectMapper objectMapper;
    /** RocketMQ 5.x 客户端工厂。 */
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    /** 生命周期内持有的 PushConsumer；功能关闭时为空。 */
    private PushConsumer consumer;
    /** 可选 Micrometer 注册器，不影响事件消费主流程。 */
    private MeterRegistry meterRegistry;

    /**
     * @param properties RocketMQ 配置
     * @param inboxRepository Inbox 仓库
     * @param objectMapper Spring JSON 映射器
     */
    public RocketMqEventAuditConsumer(
            RocketMqProperties properties,
            InboxRepository inboxRepository,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.inboxRepository = inboxRepository;
        this.objectMapper = objectMapper;
    }

    /** @param registry 存在 Actuator/Micrometer 时注入指标注册器 */
    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        meterRegistry = registry;
    }

    /** 根据功能开关创建消费者，并订阅 Commerce 与 Ops 两个主题的全部事件。 */
    @PostConstruct
    void start() throws Exception {
        if (!properties.isEnabled() || !properties.isConsumerEnabled()) {
            return;
        }
        ClientConfiguration configuration = clientConfiguration(properties);
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(properties.getAuditConsumerGroup())
                .setSubscriptionExpressions(Map.of(
                        properties.getCommerceTopic(), FilterExpression.SUB_ALL,
                        properties.getOpsTopic(), FilterExpression.SUB_ALL
                ))
                .setMessageListener(this::consume)
                .setConsumptionThreadCount(2)
                .build();
    }

    /**
     * 构建 RocketMQ 客户端连接；accessKey 与 secretKey 必须成对配置。
     *
     * @param properties 连接与凭证配置
     * @return 客户端公共配置
     */
    static ClientConfiguration clientConfiguration(RocketMqProperties properties) {
        ClientConfigurationBuilder builder = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .setRequestTimeout(properties.getRequestTimeout())
                .enableSsl(properties.isSslEnabled());
        String accessKey = properties.getAccessKey() == null ? "" : properties.getAccessKey().trim();
        String secretKey = properties.getSecretKey() == null ? "" : properties.getSecretKey().trim();
        if (!accessKey.isEmpty() || !secretKey.isEmpty()) {
            if (accessKey.isEmpty() || secretKey.isEmpty()) {
                throw new IllegalStateException("RocketMQ access key and secret key must be configured together");
            }
            builder.setCredentialProvider(new StaticSessionCredentialsProvider(accessKey, secretKey));
        }
        return builder.build();
    }

    /**
     * 解析并登记单条 RocketMQ 消息。
     *
     * @return 成功分类时 ACK；解析、数据库或校验失败时请求 RocketMQ 重投
     */
    private ConsumeResult consume(MessageView message) {
        try {
            byte[] body = bytes(message.getBody());
            JsonNode envelope = objectMapper.readTree(body);
            // 哈希覆盖原始字节，确保同 eventId 的任何载荷差异都能被 Inbox 发现。
            InboxOutcome outcome = inboxRepository.process(
                    properties.getAuditConsumerGroup(),
                    required(envelope, "eventId").asText(),
                    sha256(body),
                    required(envelope, "eventType").asText(),
                    required(envelope, "aggregateType").asText(),
                    required(envelope, "aggregateId").asText(),
                    required(envelope, "aggregateVersion").asLong()
            );
            count("citycopilot.rocketmq.consume", "outcome", outcome.name().toLowerCase());
            if (outcome == InboxOutcome.QUARANTINED) {
                count("citycopilot.rocketmq.quarantine", "reason", "payload_hash_conflict");
            }
            return ConsumeResult.SUCCESS;
        } catch (Exception error) {
            count("citycopilot.rocketmq.consume", "outcome", "failure");
            return ConsumeResult.FAILURE;
        }
    }

    /** 应用关闭时释放消费者网络线程和连接。 */
    @PreDestroy
    void close() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    /** @return 信封中的必填字段；缺失时抛出参数异常触发消息重投 */
    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Event envelope field is required: " + field);
        }
        return value;
    }

    /** @return 不改变原 ByteBuffer position 的消息体字节副本 */
    private byte[] bytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    /** @return 载荷的十六进制 SHA-256 哈希 */
    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    /** 在指标系统可用时递增带单个标签的计数器。 */
    private void count(String name, String tagName, String tagValue) {
        if (meterRegistry != null) {
            Counter.builder(name).tag(tagName, tagValue).register(meterRegistry).increment();
        }
    }
}
