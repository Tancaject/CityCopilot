package com.citycopilot.agent.ops.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpsCaseStatusTest {
    @Test
    void enforcesApprovalAndTerminalStateBoundaries() {
        assertDoesNotThrow(() -> OpsCaseStatus.AWAITING_APPROVAL.requireTransitionTo(OpsCaseStatus.APPROVED));
        assertDoesNotThrow(() -> OpsCaseStatus.EXECUTING.requireTransitionTo(OpsCaseStatus.VERIFYING));
        assertThrows(
                IllegalStateException.class,
                () -> OpsCaseStatus.AWAITING_APPROVAL.requireTransitionTo(OpsCaseStatus.EXECUTING)
        );
        assertThrows(
                IllegalStateException.class,
                () -> OpsCaseStatus.SUCCEEDED.requireTransitionTo(OpsCaseStatus.EXECUTING)
        );
    }
}
