package com.citycopilot.commerce.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disabling new admission must not abandon previously accepted bookings. */
@Component
@ConditionalOnProperty(prefix = "citycopilot.booking", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class BookingWorkers {
    private final BookingService bookings;
    public BookingWorkers(BookingService bookings) { this.bookings = bookings; }

    @Scheduled(scheduler = "bookingScheduler", fixedDelayString = "${citycopilot.booking.expiry-poll-ms:5000}", initialDelayString = "${citycopilot.booking.expiry-poll-ms:5000}")
    public void expire() { bookings.expireDue(); }

    @Scheduled(scheduler = "bookingScheduler", fixedDelayString = "${citycopilot.booking.reminder-poll-ms:10000}", initialDelayString = "${citycopilot.booking.reminder-poll-ms:10000}")
    public void remind() { bookings.deliverDueReminders(); }
}
