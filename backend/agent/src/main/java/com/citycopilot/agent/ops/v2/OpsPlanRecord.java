package com.citycopilot.agent.ops.v2;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record OpsPlanRecord(
        String id,
        String caseId,
        int version,
        String source,
        Map<String, Object> diagnosis,
        List<OpsPlanStep> steps,
        String planHash,
        String status,
        Instant createdAt
) {
}
