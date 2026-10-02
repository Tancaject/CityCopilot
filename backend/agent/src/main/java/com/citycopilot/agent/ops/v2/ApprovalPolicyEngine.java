package com.citycopilot.agent.ops.v2;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Ops 自主计划的集中审批策略边界。
 *
 * <p>当前剧本只允许受限库存修复，且每次写入都必须取得显式、会过期的审批。策略独立于规划器，防止模型或手工请求通过计划内容削弱审批要求。</p>
 */
@Component
public class ApprovalPolicyEngine {
    /** 唯一允许进入审批流程的写工具白名单。 */
    private static final Set<String> APPROVAL_REQUIRED_TOOLS = Set.of("repair_seckill_stock");

    /**
     * 确保计划非空且所有步骤均属于强制审批白名单。
     *
     * @throws IllegalArgumentException 计划为空或包含白名单外工具
     */
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
