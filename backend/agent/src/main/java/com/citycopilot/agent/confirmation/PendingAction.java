package com.citycopilot.agent.confirmation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.citycopilot.agent.tool.ToolContractSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 等待用户确认的一次性高风险动作及其可恢复计划快照。
 *
 * @param pendingId 用户确认时提交的随机标识
 * @param toolName 当前待执行工具名
 * @param args 当前工具参数
 * @param userId 创建确认的用户，消费时必须保持一致
 * @param traceId 所属执行轨迹
 * @param summary 展示给用户的确认摘要
 * @param expiresAt 确认失效时间
 * @param planVersion 计划快照版本
 * @param planHash 防止快照被篡改的规范化哈希
 * @param originalMessage 原始用户请求
 * @param hints 路由与执行提示
 * @param steps 完整计划步骤
 * @param currentStep 当前暂停位置
 * @param completedOutputs 已完成步骤的输出
 * @param evidenceIds 已累计证据标识
 * @param verificationSteps 已完成的验证说明
 * @param toolContracts 审批绑定的工具契约；null 仅表示旧版快照
 */
public record PendingAction(
        String pendingId,
        String toolName,
        Map<String, Object> args,
        String userId,
        String traceId,
        String summary,
        Instant expiresAt,
        int planVersion,
        String planHash,
        String originalMessage,
        Map<String, Object> hints,
        List<PlanCheckpointStep> steps,
        int currentStep,
        Map<Integer, Object> completedOutputs,
        List<String> evidenceIds,
        List<String> verificationSteps,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, ToolContractSnapshot> toolContracts
) {
    public PendingAction(String pendingId, String toolName, Map<String, Object> args, String userId,
                         String traceId, String summary, Instant expiresAt, int planVersion, String planHash,
                         String originalMessage, Map<String, Object> hints, List<PlanCheckpointStep> steps,
                         int currentStep, Map<Integer, Object> completedOutputs, List<String> evidenceIds,
                         List<String> verificationSteps) {
        this(pendingId, toolName, args, userId, traceId, summary, expiresAt, planVersion, planHash,
                originalMessage, hints, steps, currentStep, completedOutputs, evidenceIds, verificationSteps, null);
    }

    public PendingAction {
        toolContracts = toolContracts == null ? null : Map.copyOf(toolContracts);
    }
}
