package com.citycopilot.agent.ops.v2;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Ops Case 的持久化生命周期状态。
 *
 * <p>状态转移白名单是执行闭环的核心边界，终态成功和取消均不可再次推进。</p>
 */
public enum OpsCaseStatus {
    /** 事件已接收，尚未采集证据。 */
    OPEN,
    /** 正在调用只读工具采集新鲜证据。 */
    COLLECTING_EVIDENCE,
    /** 自动规划不足或失败，等待运维人员处理。 */
    MANUAL_REVIEW,
    /** 计划已验证并固化。 */
    PLAN_READY,
    /** 等待人工审批计划哈希。 */
    AWAITING_APPROVAL,
    /** 审批通过，等待执行。 */
    APPROVED,
    /** 正在执行受限写工具。 */
    EXECUTING,
    /** 写入后正在只读复核。 */
    VERIFYING,
    /** 可重试失败的延迟等待状态。 */
    RETRY_WAIT,
    /** 修复和验证均成功。 */
    SUCCEEDED,
    /** 达到失败边界的终止状态。 */
    FAILED,
    /** 人工取消的终止状态。 */
    CANCELLED;

    /** 每个状态允许到达的下一状态集合。 */
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

    /**
     * 校验目标状态是否在白名单中。
     *
     * @throws IllegalStateException 非法跳转时拒绝数据库更新
     */
    public void requireTransitionTo(OpsCaseStatus target) {
        if (!TRANSITIONS.getOrDefault(this, Set.of()).contains(target)) {
            throw new IllegalStateException("Invalid Ops Case transition: " + this + " -> " + target);
        }
    }
}
