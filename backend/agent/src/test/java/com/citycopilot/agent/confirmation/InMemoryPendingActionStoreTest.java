package com.citycopilot.agent.confirmation;

import com.citycopilot.agent.gateway.ToolCallContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InMemoryPendingActionStoreTest {

    @Test
    void pendingActionExpiresAndCanOnlyBeConsumedOnce() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        PendingAction action = store.create(
                "pay_order",
                Map.of("orderId", 7),
                new ToolCallContext("user-1", Set.of("order:write")),
                "trace-1",
                Duration.ofMinutes(5)
        );

        assertTrue(store.find(action.pendingId()).isPresent());
        assertTrue(PlanCheckpointIntegrity.valid(action));
        assertTrue(store.consume(action.pendingId()).isPresent());
        assertTrue(store.consume(action.pendingId()).isEmpty());

        PendingAction expired = store.create(
                "pay_order",
                Map.of("orderId", 8),
                new ToolCallContext("user-1", Set.of("order:write")),
                "trace-2",
                Duration.ZERO
        );
        assertTrue(store.find(expired.pendingId()).isEmpty());
    }

    @Test
    void changingApprovedArgumentsInvalidatesPlanHash() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        PendingAction action = store.create(
                "pay_order",
                Map.of("orderId", 7),
                new ToolCallContext("user-1", Set.of("order:write")),
                "trace-1",
                Duration.ofMinutes(5)
        );
        PendingAction tampered = new PendingAction(
                action.pendingId(),
                action.toolName(),
                Map.of("orderId", 8),
                action.userId(),
                action.traceId(),
                action.summary(),
                action.expiresAt(),
                action.planVersion(),
                action.planHash(),
                action.originalMessage(),
                action.hints(),
                java.util.List.of(new PlanCheckpointStep(1, "pay_order", Map.of("orderId", 8), true)),
                action.currentStep(),
                action.completedOutputs(),
                action.evidenceIds(),
                action.verificationSteps()
        );

        assertFalse(PlanCheckpointIntegrity.valid(tampered));
    }
}
