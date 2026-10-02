package com.citycopilot.commerce.booking;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record BookingPolicy(int courtSlotMinutes, int publishingDays, int holdSeconds,
                            int maxActiveHolds, int maxFutureBookings, int maxDiningPartySize,
                            int cancellationMinutes, int checkinEarlyMinutes, int reminderMinutes,
                            String defaultTimezone, String checkinSecret, boolean newBookingsEnabled) {
    public BookingPolicy(String checkinSecret) {
        this(60, 14, 120, 1, 3, 8, 30, 15, 30, "Asia/Shanghai", checkinSecret, true);
    }

    @Autowired
    public BookingPolicy(
            @Value("${citycopilot.booking.court-slot-minutes:60}") int courtSlotMinutes,
            @Value("${citycopilot.booking.publishing-days:14}") int publishingDays,
            @Value("${citycopilot.booking.hold-seconds:120}") int holdSeconds,
            @Value("${citycopilot.booking.max-active-holds:1}") int maxActiveHolds,
            @Value("${citycopilot.booking.max-future-bookings:3}") int maxFutureBookings,
            @Value("${citycopilot.booking.max-dining-party-size:8}") int maxDiningPartySize,
            @Value("${citycopilot.booking.cancellation-minutes:30}") int cancellationMinutes,
            @Value("${citycopilot.booking.checkin-early-minutes:15}") int checkinEarlyMinutes,
            @Value("${citycopilot.booking.reminder-minutes:30}") int reminderMinutes,
            @Value("${citycopilot.booking.default-timezone:Asia/Shanghai}") String defaultTimezone,
            @Value("${citycopilot.booking.checkin-secret:${citycopilot.commerce.service-token:}}") String checkinSecret,
            @Value("${citycopilot.booking.new-bookings-enabled:true}") boolean newBookingsEnabled) {
        if (courtSlotMinutes < 1 || publishingDays < 1 || publishingDays > 366 || holdSeconds < 1
                || maxActiveHolds < 1 || maxFutureBookings < maxActiveHolds || maxDiningPartySize < 1
                || cancellationMinutes < 0 || checkinEarlyMinutes < 0 || reminderMinutes < 0) {
            throw new IllegalArgumentException("Invalid booking business policy");
        }
        java.time.ZoneId.of(defaultTimezone);
        this.courtSlotMinutes = courtSlotMinutes;
        this.publishingDays = publishingDays;
        this.holdSeconds = holdSeconds;
        this.maxActiveHolds = maxActiveHolds;
        this.maxFutureBookings = maxFutureBookings;
        this.maxDiningPartySize = maxDiningPartySize;
        this.cancellationMinutes = cancellationMinutes;
        this.checkinEarlyMinutes = checkinEarlyMinutes;
        this.reminderMinutes = reminderMinutes;
        this.defaultTimezone = defaultTimezone;
        this.checkinSecret = checkinSecret;
        this.newBookingsEnabled = newBookingsEnabled;
    }
}
