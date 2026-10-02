package com.citycopilot.agent.ops.v2;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.citycopilot.agent.tool.ToolContractSnapshot;

/**
 * Ops 修复计划中的单个受限工具步骤。
 *
 * @param toolName 工具注册名
 * @param arguments 已绑定证据的参数
 * @param purpose 面向审批人的步骤目的
 * @param contract 审批绑定的写工具契约；null 仅表示旧版步骤
 * @param verificationContract 独立复核工具契约，与写步骤一起审批
 */
public record OpsPlanStep(
        String toolName,
        Map<String, Object> arguments,
        String purpose,
        @JsonInclude(JsonInclude.Include.NON_NULL) ToolContractSnapshot contract,
        @JsonInclude(JsonInclude.Include.NON_NULL) ToolContractSnapshot verificationContract
) {
    public static final String VERIFICATION_TOOL = "verify_seckill_consistency";
    public OpsPlanStep(String toolName, Map<String, Object> arguments, String purpose) {
        this(toolName, arguments, purpose, null, null);
    }

    /** 固化参数副本，保证计划哈希和审批内容不会被外部修改。 */
    public OpsPlanStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
