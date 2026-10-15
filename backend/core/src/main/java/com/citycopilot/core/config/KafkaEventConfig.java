package com.citycopilot.core.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * Kafka 消费失败处理：数据库等暂时性故障按指数退避重试，仍失败或消息本身不合法时转发到同分区的 {@code <topic>.DLT}。
 *
 * <p>Spring Boot 会把这个 {@link CommonErrorHandler} 装配到默认监听容器；offset 只在处理成功或转入死信后提交，
 * 所以投递语义是至少一次，重复由 Inbox 去重。</p>
 */
@Configuration
public class KafkaEventConfig {
    /** 首次失败后的重试次数；全部失败再进死信。 */
    static final int RETRIES = 3;

    @Bean
    CommonErrorHandler eventErrorHandler(KafkaTemplate<String, byte[]> deadLetters) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxAttempts(RETRIES);
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(deadLetters, (record, error) -> deadLetterTopic(record)),
                backOff);
        // 信封不合法或 JSON 坏了，重试也不会好，直接进死信，不阻塞分区。
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
        return handler;
    }

    /** @return 原主题加 {@code .DLT}、保持原分区号；死信主题按同样分区数预先创建 */
    static TopicPartition deadLetterTopic(ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic() + ".DLT", record.partition());
    }
}
