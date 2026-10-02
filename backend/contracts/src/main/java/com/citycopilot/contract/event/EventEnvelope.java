package com.citycopilot.contract.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Shared wire fields; Outbox replay republishes the stored envelope without changing its identity. */
public record EventEnvelope<T>(String eventId, String eventType, int schemaVersion,
                               String aggregateType, String aggregateId, long aggregateVersion,
                               String sourceService, String occurredAt, String correlationId, T payload) {
    public EventEnvelope {
        if (!UUID.fromString(eventId).toString().equals(eventId)) {
            throw new IllegalArgumentException("eventId must be a canonical UUID");
        }
        required(eventType, "eventType");
        required(aggregateType, "aggregateType");
        required(aggregateId, "aggregateId");
        required(sourceService, "sourceService");
        required(correlationId, "correlationId");
        if (schemaVersion < 1 || aggregateVersion < 0) {
            throw new IllegalArgumentException("Event schemaVersion must be positive and aggregateVersion non-negative");
        }
        // Keep the wire timestamp textual regardless of a producer's Jackson date defaults.
        Instant.parse(occurredAt);
        Objects.requireNonNull(payload, "payload");
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Event field required: " + field);
    }
}
