package com.citycopilot.core.event;

import com.citycopilot.core.booking.BookingBehaviorProjector;
import com.citycopilot.core.booking.BookingFeedbackPolicy;
import com.citycopilot.core.config.RocketMqProperties;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingProjectionMetricsTest {
    @Test
    void deliveryAndAckDoNotMasqueradeAsCommittedProjectionOrIdleHealth() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var observations = new BookingProjectionMetrics(registry);
            var projector = mock(BookingBehaviorProjector.class);
            var consumer = new RocketMqBookingBehaviorConsumer(new RocketMqProperties(),
                    new BookingFeedbackPolicy(false, 30, 60, 3600000, "disabled-check"), projector, observations);
            consumer.start();
            assertThat(registry.scrape())
                    .contains("citycopilot_booking_projection_consumer_active 0.0")
                    .contains("citycopilot_booking_projection_last_commit_seconds 0.0")
                    .contains("citycopilot_booking_projection_last_receipt_seconds 0.0")
                    .contains("citycopilot_booking_projection_last_delay_seconds NaN")
                    .contains("citycopilot_booking_projection_delay_seconds_count 0")
                    .contains("citycopilot_booking_projection_clock_skew_total 0.0");
            var message = mock(MessageView.class);
            when(message.getBody()).thenReturn(ByteBuffer.wrap(new byte[]{1}));
            when(projector.process(any())).thenThrow(new IllegalArgumentException("invalid event"))
                    .thenReturn(InboxOutcome.PROCESSED);
            assertThat((ConsumeResult) ReflectionTestUtils.invokeMethod(consumer, "consume", message))
                    .isEqualTo(ConsumeResult.FAILURE);
            assertThat(registry.get("citycopilot.booking.business.consume").tag("outcome", "failure").counter().count()).isEqualTo(1);
            assertThat(registry.get("citycopilot.booking.projection.last.receipt").gauge().value()).isPositive();
            assertThat((ConsumeResult) ReflectionTestUtils.invokeMethod(consumer, "consume", message))
                    .isEqualTo(ConsumeResult.SUCCESS);
            assertThat(registry.get("citycopilot.booking.projection.delay").timer().count()).isZero();
            assertThat(registry.get("citycopilot.booking.projection.last.commit").gauge().value()).isZero();
            consumer.close();
        } finally {
            registry.close();
        }
    }
}
