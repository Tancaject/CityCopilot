package com.citycopilot.contract.event;

import java.time.Instant;

public record OrderCreatedEvent(
        long orderId,
        long userId,
        long voucherId,
        int status,
        int version,
        Instant createdAt
) {
    public static final String EVENT_TYPE = "commerce.order.created";
    public static final int SCHEMA_VERSION = 1;
}
