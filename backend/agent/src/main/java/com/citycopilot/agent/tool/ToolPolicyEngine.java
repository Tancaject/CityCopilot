package com.citycopilot.agent.tool;

import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolRiskLevel;

import java.util.List;

/**
 * 工具受众可见性与审批要求的确定性策略器。
 *
 * <p>执行器在把目录交给模型前先按受众过滤，并同时以风险级别和审批策略判断高风险，不能只信任模型计划标记。</p>
 */
public final class ToolPolicyEngine {

    /** @return 工具面向所有受众或明确面向目标受众时为 true */
    public boolean visibleTo(ToolMetadata tool, ToolAudience audience) {
        return tool != null
                && (tool.audience() == ToolAudience.ALL || tool.audience() == audience);
    }

    /** @return 保持原顺序的目标受众可见工具列表 */
    public List<ToolMetadata> visibleTools(List<ToolMetadata> tools, ToolAudience audience) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream().filter(tool -> visibleTo(tool, audience)).toList();
    }

    /** @return 高风险或声明任一审批策略时为 true */
    public boolean requiresApproval(ToolMetadata tool) {
        return tool != null
                && (tool.risk() == ToolRiskLevel.HIGH || tool.approval() != ToolApprovalPolicy.NONE);
    }
}
