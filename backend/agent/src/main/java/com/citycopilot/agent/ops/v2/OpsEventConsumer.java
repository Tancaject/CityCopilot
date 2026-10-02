package com.citycopilot.agent.ops.v2;

import com.citycopilot.contract.event.StockDiscrepancyDetectedEvent;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * 库存差异领域事件的 RocketMQ 消费者。
 *
 * <p>验证事件信封后交给仓储做事件幂等与事故聚合；未知事件视为已忽略，解析或持久化失败返回 FAILURE 让 RocketMQ 重投。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.rocketmq",
        name = "consumer-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OpsEventConsumer {
    /** RocketMQ 端点、主题和凭据配置。 */
    private final AgentRocketMqProperties rocketMq;
    /** v2 总开关和读取线程配置。 */
    private final OpsV2Properties opsProperties;
    /** 原子接受事件并创建/合并 case 的仓储。 */
    private final OpsCaseRepository repository;
    /** 解析版本化事件信封。 */
    private final ObjectMapper objectMapper;
    /** RocketMQ 5.x 客户端服务提供者。 */
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    /** 生命周期内持有的推送消费者。 */
    private PushConsumer consumer;
    /** 可选事件消费指标注册器。 */
    private MeterRegistry meterRegistry;

    public OpsEventConsumer(
            AgentRocketMqProperties rocketMq,
            OpsV2Properties opsProperties,
            OpsCaseRepository repository,
            ObjectMapper objectMapper
    ) {
        this.rocketMq = rocketMq;
        this.opsProperties = opsProperties;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        meterRegistry = registry;
    }

    /** v2 和 RocketMQ 均启用时创建 PushConsumer 并订阅运维主题。 */
    @PostConstruct
    void start() throws Exception {
        if (!opsProperties.isEnabled() || !rocketMq.isEnabled()) {
            return;
        }
        ClientConfiguration configuration = clientConfiguration(rocketMq);
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(rocketMq.getConsumerGroup())
                .setSubscriptionExpressions(Map.of(rocketMq.getOpsTopic(), FilterExpression.SUB_ALL))
                .setMessageListener(this::consume)
                .setConsumptionThreadCount(Math.max(1, opsProperties.getReaderThreads()))
                .build();
    }

    /**
     * 构建 RocketMQ 客户端配置，并拒绝只配置一半的访问密钥。
     */
    static ClientConfiguration clientConfiguration(AgentRocketMqProperties properties) {
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
     * 消费单个事件并按 eventId/载荷哈希接受到 Ops Case。
     *
     * @return 成功、重复和忽略事件均 ACK；异常返回 FAILURE 触发消息重试
     */
    private ConsumeResult consume(MessageView message) {
        try {
            byte[] body = bytes(message.getBody());
            JsonNode envelope = objectMapper.readTree(body);
            String eventType = required(envelope, "eventType").asText();
            if (!StockDiscrepancyDetectedEvent.EVENT_TYPE.equals(eventType)) {
                count("ignored");
                return ConsumeResult.SUCCESS;
            }
            JsonNode payload = required(envelope, "payload");
            // 仓储在同一事务中判定重复/冲突并创建或关联 case，消费者无需先查后写。
            OpsCaseRepository.EventAcceptance acceptance = repository.acceptEvent(
                    rocketMq.getConsumerGroup(),
                    required(envelope, "eventId").asText(),
                    eventType,
                    sha256(body),
                    new String(body, StandardCharsets.UTF_8),
                    required(payload, "incidentKey").asText()
            );
            count(acceptance.disposition().name().toLowerCase());
            return ConsumeResult.SUCCESS;
        } catch (Exception error) {
            count("failure");
            return ConsumeResult.FAILURE;
        }
    }

    /** 容器停止时关闭消费者并等待客户端释放资源。 */
    @PreDestroy
    void close() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Ops event field is required: " + field);
        }
        return value;
    }

    private byte[] bytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            Counter.builder("citycopilot.ops.event.consume")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
