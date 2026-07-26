package com.citycopilot.agent.ops.v2;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OpsCaseStatus {
    OPEN,
    COLLECTING_EVIDENCE,
    MANUAL_REVIEW,
    PLAN_READY,
    AWAITING_APPROVAL,
    APPROVED,
    EXECUTING,
    VERIFYING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Map<OpsCaseStatus, Set<OpsCaseStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(OPEN, EnumSet.of(COLLECTING_EVIDENCE, CANCELLED)),
            Map.entry(COLLECTING_EVIDENCE, EnumSet.of(MANUAL_REVIEW, PLAN_READY, RETRY_WAIT, FAILED, CANCELLED)),
            Map.entry(MANUAL_REVIEW, EnumSet.of(PLAN_READY, RETRY_WAIT, CANCELLED)),
            Map.entry(PLAN_READY, EnumSet.of(AWAITING_APPROVAL, MANUAL_REVIEW, CANCELLED)),
            Map.entry(AWAITING_APPROVAL, EnumSet.of(APPROVED, MANUAL_REVIEW, CANCELLED)),
            Map.entry(APPROVED, EnumSet.of(EXECUTING, CANCELLED)),
            Map.entry(EXECUTING, EnumSet.of(VERIFYING, RETRY_WAIT, MANUAL_REVIEW, FAILED)),
            Map.entry(VERIFYING, EnumSet.of(SUCCEEDED, RETRY_WAIT, MANUAL_REVIEW, FAILED)),
            Map.entry(RETRY_WAIT, EnumSet.of(COLLECTING_EVIDENCE, EXECUTING, VERIFYING, MANUAL_REVIEW, CANCELLED)),
            Map.entry(SUCCEEDED, EnumSet.noneOf(OpsCaseStatus.class)),
            Map.entry(FAILED, EnumSet.of(MANUAL_REVIEW, RETRY_WAIT)),
            Map.entry(CANCELLED, EnumSet.noneOf(OpsCaseStatus.class))
    );

    public void requireTransitionTo(OpsCaseStatus target) {
        if (!TRANSITIONS.getOrDefault(this, Set.of()).contains(target)) {
            throw new IllegalStateException("Invalid Ops Case transition: " + this + " -> " + target);
        }
    }
}
