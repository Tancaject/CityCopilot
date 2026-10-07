package com.citycopilot.contract.booking;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared booking HTTP contract; IDs remain strings across browser and service boundaries. */
public final class BookingContracts {
    private BookingContracts() {}

    public record ShopView(String id, String name) {}

    public record Rules(int courtSlotMinutes, int publishingDays, int holdSeconds,
                        int maxActiveHolds, int maxFutureBookings, int maxDiningPartySize,
                        int cancellationMinutes, int checkinEarlyMinutes, int reminderMinutes,
                        String defaultTimezone, Instant serverTime, boolean newBookingsEnabled) {}

    public record ResourceRequest(String kind, String name, String timezone, Integer maxPartySize,
                                  String status, Long expectedVersion) {}
    public record ResourceView(String id, String shopId, String kind, String name, String timezone,
                               int maxPartySize, String status, long version) {}
    public record SlotRequest(String slotId, Instant startAt, Instant endAt, Integer capacity,
                              String status, Long expectedVersion) {}
    public record SlotView(String id, String resourceId, Instant startAt, Instant endAt,
                           int capacity, int occupied, int available, String status, long version) {}
    public record HoldRequest(String resourceId, String slotId, int partySize, String quoteId,
                              String source, String requestId) {}
    public record ActionRequest(Long expectedVersion, String reason, String token) {}
    public record RescheduleRequest(Long expectedVersion, String resourceId, String slotId,
                                    int partySize, String quoteId) {}
    public record BookingView(String id, String userId, String shopId, String resourceId,
                              String resourceName, String kind, String slotId, int partySize,
                              int units, String status, String reasonCode, Instant startAt,
                              Instant endAt, Instant holdExpiresAt, long version, long scheduleVersion,
                              Map<String, Object> priceSnapshot, String checkinToken,
                              List<String> allowedActions, Instant serverTime) {}
    public record MutationResult(String code, int httpStatus, String message, BookingView booking) {}
    public record CommandView(String idempotencyKey, String action, String bookingId,
                              MutationResult result, Instant createdAt) {}
    public record NoticeView(String id, String bookingId, long scheduleVersion, String kind,
                             String message, Instant dueAt, Instant deliveredAt, Instant readAt) {}
}
