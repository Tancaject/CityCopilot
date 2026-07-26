package com.citycopilot.agent.confirmation;

import com.citycopilot.agent.gateway.ToolCallContext;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static com.citycopilot.agent.confirmation.PlanCheckpointIntegrity.hash;

public final class InMemoryPendingActionStore implements PendingActionStore {
    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();

    @Override
    public PendingAction createCheckpoint(
            PlanCheckpoint checkpoint,
            ToolCallContext context,
            String traceId,
            Duration ttl
    ) {
        String pendingId = UUID.randomUUID().toString();
        PlanCheckpointStep current = checkpoint.steps().get(checkpoint.currentStep());
        PendingAction action = new PendingAction(
                pendingId,
                current.toolName(),
                current.args(),
                context == null ? "anonymous" : context.userId(),
                traceId,
                summary(current.toolName(), current.args()),
                Instant.now().plus(ttl),
                checkpoint.planVersion(),
                hash(checkpoint),
                checkpoint.originalMessage(),
                checkpoint.hints(),
                checkpoint.steps(),
                checkpoint.currentStep(),
                checkpoint.completedOutputs(),
                checkpoint.evidenceIds(),
                checkpoint.verificationSteps()
        );
        actions.put(pendingId, action);
        return action;
    }

    @Override
    public Optional<PendingAction> find(String pendingId) {
        PendingAction action = actions.get(pendingId);
        if (expired(action)) {
            actions.remove(pendingId, action);
            return Optional.empty();
        }
        return Optional.ofNullable(action);
    }

    @Override
    public Optional<PendingAction> consume(String pendingId) {
        PendingAction action = actions.remove(pendingId);
        return expired(action) ? Optional.empty() : Optional.ofNullable(action);
    }

    private boolean expired(PendingAction action) {
        return action != null && !action.expiresAt().isAfter(Instant.now());
    }

    private String summary(String toolName, Map<String, Object> args) {
        return "确认执行 " + toolName + "，参数：" + (args == null ? Map.of() : args);
    }
}
