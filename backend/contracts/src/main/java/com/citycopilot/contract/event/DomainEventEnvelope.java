package com.citycopilot.contract.event;

import java.time.Instant;

public record DomainEventEnvelope<T>(
        String eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        String sourceService,
        Instant occurredAt,
        String traceId,
        String correlationId,
        String causationId,
        T payload
) {
}
