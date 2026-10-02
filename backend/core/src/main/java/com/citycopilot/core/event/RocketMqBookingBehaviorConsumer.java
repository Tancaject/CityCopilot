package com.citycopilot.core.event;

import com.citycopilot.core.booking.BookingBehaviorProjector;
import com.citycopilot.core.booking.BookingFeedbackPolicy;
import com.citycopilot.core.config.RocketMqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.Map;

/** 独立消费组只订阅 BookingEvent，复用审计消费者的连接配置与 Inbox。 */
@Component
public class RocketMqBookingBehaviorConsumer {
    private static final Logger log = LoggerFactory.getLogger(RocketMqBookingBehaviorConsumer.class);
    private final RocketMqProperties mq;
    private final BookingFeedbackPolicy policy;
    private final BookingBehaviorProjector projector;
    private final BookingProjectionMetrics metrics;
    private PushConsumer consumer;

    public RocketMqBookingBehaviorConsumer(RocketMqProperties mq, BookingFeedbackPolicy policy,
                                          BookingBehaviorProjector projector, BookingProjectionMetrics metrics) {
        this.mq = mq; this.policy = policy; this.projector = projector; this.metrics = metrics;
    }
    @PostConstruct
    void start() throws Exception {
        if (!policy.enabled() || !mq.isEnabled() || !mq.isConsumerEnabled()) return;
        consumer = ClientServiceProvider.loadService().newPushConsumerBuilder()
                .setClientConfiguration(RocketMqEventAuditConsumer.clientConfiguration(mq))
                .setConsumerGroup(policy.consumerGroup())
                .setSubscriptionExpressions(Map.of(mq.getCommerceTopic(), new FilterExpression("BookingEvent")))
                .setConsumptionThreadCount(2).setMessageListener(this::consume).build();
        metrics.active(true);
    }
    private ConsumeResult consume(MessageView message) {
        metrics.received();
        try {
            ByteBuffer buffer = message.getBody().asReadOnlyBuffer();
            byte[] body = new byte[buffer.remaining()]; buffer.get(body);
            projector.process(body);
            return ConsumeResult.SUCCESS;
        } catch (Exception error) {
            metrics.failed();
            log.warn("Booking behavior event handling failed; RocketMQ will retry the original event identity", error);
            return ConsumeResult.FAILURE;
        }
    }
    @PreDestroy
    void close() throws Exception {
        metrics.active(false);
        if (consumer != null) consumer.close();
    }
}
