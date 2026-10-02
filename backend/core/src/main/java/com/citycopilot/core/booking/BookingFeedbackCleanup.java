package com.citycopilot.core.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "citycopilot.booking-feedback", name = "enabled", havingValue = "true")
public class BookingFeedbackCleanup {
    private final BookingFeedbackService feedback;
    public BookingFeedbackCleanup(BookingFeedbackService feedback) { this.feedback = feedback; }

    @Scheduled(initialDelayString = "${citycopilot.booking-feedback.cleanup-interval-ms:3600000}",
            fixedDelayString = "${citycopilot.booking-feedback.cleanup-interval-ms:3600000}")
    public void cleanup() { feedback.cleanupExpired(); }
}
