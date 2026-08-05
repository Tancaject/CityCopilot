package com.citycopilot.agent.ops.v2;

import com.citycopilot.contract.event.StockDiscrepancyDetectedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Component
@ConditionalOnProperty(
        prefix = "citycopilot.rocketmq",
        name = "consumer-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OpsEventConsumer {
    private final AgentRocketMqProperties rocketMq;
    private final OpsV2Properties opsProperties;
    private final OpsCaseRepository repository;
    private final ObjectMapper objectMapper;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private PushConsumer consumer;
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
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        meterRegistry = registry;
    }

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
