package com.citycopilot.core.event;

public record OutboxRecord(
        String eventId,
        String eventType,
        String topic,
        String tag,
        String payloadJson,
        String payloadHash,
        String claimToken,
        long fencingToken,
        int attempts
) {
}
