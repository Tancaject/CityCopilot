package com.citycopilot.agent.confirmation;

import com.citycopilot.agent.gateway.ToolCallContext;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public interface PendingActionStore {
    default PendingAction create(
            String toolName,
            Map<String, Object> args,
            ToolCallContext context,
            String traceId,
            Duration ttl
    ) {
        return createCheckpoint(
                new PlanCheckpoint(
                        1,
                        "",
                        Map.of(),
                        java.util.List.of(new PlanCheckpointStep(1, toolName, args, true)),
                        0,
                        Map.of(),
                        java.util.List.of(),
                        java.util.List.of()
                ),
                context,
                traceId,
                ttl
        );
    }

    PendingAction createCheckpoint(
            PlanCheckpoint checkpoint,
            ToolCallContext context,
            String traceId,
            Duration ttl
    );

    Optional<PendingAction> find(String pendingId);

    Optional<PendingAction> consume(String pendingId);
}
