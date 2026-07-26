package com.citycopilot.agent.ops.v2;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Central policy boundary for autonomous Ops plans.
 *
 * <p>The current playbook intentionally allows only a single bounded repair
 * operation, and every such write must pass an explicit, expiring approval.
 * Keeping this decision outside the planner prevents an LLM or manual caller
 * from weakening the approval requirement through plan content.</p>
 */
@Component
public class ApprovalPolicyEngine {
    private static final Set<String> APPROVAL_REQUIRED_TOOLS = Set.of("repair_seckill_stock");

    public void requireApproval(List<OpsPlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("An Ops plan must contain at least one step");
        }
        boolean containsUnapprovedTool = steps.stream()
                .anyMatch(step -> !APPROVAL_REQUIRED_TOOLS.contains(step.toolName()));
        if (containsUnapprovedTool) {
            throw new IllegalArgumentException("Ops plan contains a tool outside the approval policy");
        }
    }
}
