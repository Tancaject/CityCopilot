package com.citycopilot.agent.ops.v2;

import java.time.Instant;

public record OpsCaseRecord(
        String id,
        String incidentKey,
        String eventId,
        String caseType,
        OpsCaseStatus status,
        int version,
        int currentStep,
        int retryCount,
        boolean cancellationRequested,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
