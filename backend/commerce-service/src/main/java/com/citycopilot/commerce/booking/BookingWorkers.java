package com.citycopilot.commerce.booking;

import com.citycopilot.commerce.CommerceJobLease;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disabling new admission must not abandon previously accepted bookings. */
@Component
@ConditionalOnProperty(prefix = "citycopilot.booking", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class BookingWorkers {
    private final BookingService bookings;
    private final CommerceJobLease leases;
    public BookingWorkers(BookingService bookings, CommerceJobLease leases) {
        this.bookings = bookings;
        this.leases = leases;
    }

    @Scheduled(scheduler = "bookingScheduler", fixedDelayString = "${citycopilot.booking.expiry-poll-ms:5000}", initialDelayString = "${citycopilot.booking.expiry-poll-ms:5000}")
    public void expire() {
        leases.acquire("booking-expiry").ifPresent(lease -> bookings.expireDue(lease::runFenced));
    }

    @Scheduled(scheduler = "bookingScheduler", fixedDelayString = "${citycopilot.booking.reminder-poll-ms:10000}", initialDelayString = "${citycopilot.booking.reminder-poll-ms:10000}")
    public void remind() {
        leases.acquire("booking-reminder").ifPresent(lease -> bookings.deliverDueReminders(lease::runFenced));
    }
}
