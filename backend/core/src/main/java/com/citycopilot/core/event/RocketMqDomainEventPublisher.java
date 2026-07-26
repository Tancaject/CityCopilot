package com.citycopilot.core.event;

import com.citycopilot.core.config.RocketMqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class RocketMqDomainEventPublisher implements DomainEventPublisher {
    private final RocketMqProperties properties;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private Producer producer;

    public RocketMqDomainEventPublisher(RocketMqProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void start() throws Exception {
        if (!properties.isEnabled() || !properties.isProducerEnabled()) {
            return;
        }
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .setRequestTimeout(properties.getRequestTimeout())
                .build();
        producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(properties.getCommerceTopic(), properties.getOpsTopic())
                .setMaxAttempts(properties.getMaxAttempts())
                .build();
    }

    @Override
    public String publish(OutboxRecord record) throws Exception {
        if (producer == null) {
            throw new IllegalStateException("RocketMQ producer is disabled");
        }
        Message message = provider.newMessageBuilder()
                .setTopic(record.topic())
                .setTag(record.tag())
                .setKeys(record.eventId())
                .addProperty("eventType", record.eventType())
                .addProperty("payloadHash", record.payloadHash())
                .setBody(record.payloadJson().getBytes(StandardCharsets.UTF_8))
                .build();
        SendReceipt receipt = producer.send(message);
        return receipt.getMessageId().toString();
    }

    @PreDestroy
    void close() throws Exception {
        if (producer != null) {
            producer.close();
        }
    }
}
