package com.citycopilot.agent.confirmation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.citycopilot.agent.tool.ToolContractSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan-Execute 在高风险步骤前保存的不可变执行检查点。
 *
 * @param planVersion 计划版本，最小为 1
 * @param originalMessage 原始用户请求
 * @param hints 执行提示快照
 * @param steps 计划的全部工具步骤
 * @param currentStep 等待执行的步骤下标
 * @param completedOutputs 已完成步骤输出
 * @param evidenceIds 已收集证据标识
 * @param verificationSteps 已记录验证步骤
 * @param toolContracts 计划和复核工具共用的审批契约快照；null 仅表示旧版快照
 */
public record PlanCheckpoint(
        int planVersion,
        String originalMessage,
        Map<String, Object> hints,
        List<PlanCheckpointStep> steps,
        int currentStep,
        Map<Integer, Object> completedOutputs,
        List<String> evidenceIds,
        List<String> verificationSteps,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, ToolContractSnapshot> toolContracts
) {
    public PlanCheckpoint(int planVersion, String originalMessage, Map<String, Object> hints,
                          List<PlanCheckpointStep> steps, int currentStep, Map<Integer, Object> completedOutputs,
                          List<String> evidenceIds, List<String> verificationSteps) {
        this(planVersion, originalMessage, hints, steps, currentStep, completedOutputs, evidenceIds, verificationSteps, null);
    }

    /** 归一化空集合并建立防御性副本，确保确认期间快照不会被调用方修改。 */
    public PlanCheckpoint {
        toolContracts = toolContracts == null ? null : Map.copyOf(toolContracts);
        planVersion = Math.max(1, planVersion);
        hints = hints == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(hints));
        steps = steps == null ? List.of() : List.copyOf(steps);
        completedOutputs = completedOutputs == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(completedOutputs));
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        verificationSteps = verificationSteps == null ? List.of() : List.copyOf(verificationSteps);
    }
}
