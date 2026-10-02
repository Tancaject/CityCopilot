package com.citycopilot.core.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record BookingFeedbackPolicy(boolean enabled, int rawRetentionDays, int cacheSeconds,
                                    long cleanupIntervalMs, String consumerGroup) {
    public BookingFeedbackPolicy(
            @Value("${citycopilot.booking-feedback.enabled:false}") boolean enabled,
            @Value("${citycopilot.booking-feedback.raw-retention-days:30}") int rawRetentionDays,
            @Value("${citycopilot.booking-feedback.cache-seconds:60}") int cacheSeconds,
            @Value("${citycopilot.booking-feedback.cleanup-interval-ms:3600000}") long cleanupIntervalMs,
            @Value("${citycopilot.booking-feedback.consumer-group:citycopilot-booking-feedback-v1}") String consumerGroup) {
        if (rawRetentionDays < 1 || rawRetentionDays > 365 || cacheSeconds < 1 || cacheSeconds > 300
                || cleanupIntervalMs < 1000 || consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("Invalid booking feedback policy");
        }
        this.enabled = enabled;
        this.rawRetentionDays = rawRetentionDays;
        this.cacheSeconds = cacheSeconds;
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.consumerGroup = consumerGroup;
    }
}
