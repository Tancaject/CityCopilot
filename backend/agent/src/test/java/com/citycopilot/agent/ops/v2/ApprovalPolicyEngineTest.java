package com.citycopilot.agent.ops.v2;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalPolicyEngineTest {
    private final ApprovalPolicyEngine engine = new ApprovalPolicyEngine();

    @Test
    void permitsOnlyTheBoundedApprovedRepairPlaybook() {
        assertDoesNotThrow(() -> engine.requireApproval(List.of(
                new OpsPlanStep("repair_seckill_stock", Map.of(
                        "voucherId", 100L,
                        "expectedEvidenceHash", "hash"
                ), "restore the Redis stock mirror")
        )));
        assertThrows(IllegalArgumentException.class, () -> engine.requireApproval(List.of(
                new OpsPlanStep("replay_seckill_dead_letter", Map.of(), "disallowed")
        )));
    }
}
