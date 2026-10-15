package com.citycopilot.core.event;

import com.citycopilot.core.config.KafkaEventProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 订阅 Commerce 与 Ops 领域事件的 Core 审计消费者。
 *
 * <p>校验统一事件信封，通过 {@link InboxRepository} 去重/判序并记录指标。异常直接抛出，由
 * {@code KafkaEventConfig} 的错误处理器重试或转入死信；重复、过期或已隔离事件正常返回、提交 offset。</p>
 */
@Component
public class KafkaEventAuditConsumer {
    /** 主题、消费组配置。 */
    private final KafkaEventProperties properties;
    /** 事件去重、冲突隔离和版本水位仓库。 */
    private final InboxRepository inboxRepository;
    /** 解析统一 JSON 事件信封。 */
    private final ObjectMapper objectMapper;
    /** 可选 Micrometer 注册器，不影响事件消费主流程。 */
    private MeterRegistry meterRegistry;

    public KafkaEventAuditConsumer(
            KafkaEventProperties properties,
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

    /**
     * 解析并登记单条事件；方法正常返回后容器才提交 offset。
     *
     * @throws Exception 信封不合法（进死信）或存储失败（退避重试）
     */
    @KafkaListener(
            id = "event-audit",
            topics = {"${citycopilot.kafka.commerce-topic}", "${citycopilot.kafka.ops-topic}"},
            groupId = "${citycopilot.kafka.audit-consumer-group}",
            concurrency = "2",
            autoStartup = "#{${citycopilot.kafka.enabled:true} and ${citycopilot.kafka.consumer-enabled:true}}"
    )
    void consume(ConsumerRecord<String, byte[]> record) throws Exception {
        try {
            byte[] body = record.value();
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
            count("citycopilot.events.consume", "outcome", outcome.name().toLowerCase());
            if (outcome == InboxOutcome.QUARANTINED) {
                count("citycopilot.events.quarantine", "reason", "payload_hash_conflict");
            }
        } catch (Exception error) {
            count("citycopilot.events.consume", "outcome", "failure");
            throw error;
        }
    }

    /** @return 信封中的必填字段；缺失时抛出参数异常，消息直接进死信 */
    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Event envelope field is required: " + field);
        }
        return value;
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
