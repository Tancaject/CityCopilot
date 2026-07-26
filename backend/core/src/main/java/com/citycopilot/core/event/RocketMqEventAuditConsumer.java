package com.citycopilot.core.event;

import com.citycopilot.core.config.RocketMqProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
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

@Component
public class RocketMqEventAuditConsumer {
    private final RocketMqProperties properties;
    private final InboxRepository inboxRepository;
    private final ObjectMapper objectMapper;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private PushConsumer consumer;
    private MeterRegistry meterRegistry;

    public RocketMqEventAuditConsumer(
            RocketMqProperties properties,
            InboxRepository inboxRepository,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.inboxRepository = inboxRepository;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        meterRegistry = registry;
    }

    @PostConstruct
    void start() throws Exception {
        if (!properties.isEnabled() || !properties.isConsumerEnabled()) {
            return;
        }
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .setRequestTimeout(properties.getRequestTimeout())
                .build();
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

    private ConsumeResult consume(MessageView message) {
        try {
            byte[] body = bytes(message.getBody());
            JsonNode envelope = objectMapper.readTree(body);
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

    @PreDestroy
    void close() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
    }

    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Event envelope field is required: " + field);
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

    private void count(String name, String tagName, String tagValue) {
        if (meterRegistry != null) {
            Counter.builder(name).tag(tagName, tagValue).register(meterRegistry).increment();
        }
    }
}
