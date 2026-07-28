package com.citycopilot.agent.memory;

import java.time.Instant;

public record UserPreference(
        long id,
        long userId,
        PreferenceType type,
        String value,
        int mentionCount,
        Instant createdAt,
        Instant updatedAt
) {
}
