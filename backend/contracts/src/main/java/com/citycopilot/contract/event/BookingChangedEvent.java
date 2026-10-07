package com.citycopilot.contract.event;

import java.time.Instant;
import java.util.Map;

public record BookingChangedEvent(String bookingId, String shopId, String userId, String resourceId,
                                  String slotId, int partySize, int units, String status, long scheduleVersion,
                                  Instant startAt, Instant endAt, Map<String, Object> priceSnapshot,
                                  String source, String requestId, long actorId) {
    public static final int SCHEMA_VERSION = 1;
}
