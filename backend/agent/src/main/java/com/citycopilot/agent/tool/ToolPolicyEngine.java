package com.citycopilot.agent.tool;

import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolRiskLevel;

import java.util.List;

public final class ToolPolicyEngine {

    public boolean visibleTo(ToolMetadata tool, ToolAudience audience) {
        return tool != null
                && (tool.audience() == ToolAudience.ALL || tool.audience() == audience);
    }

    public List<ToolMetadata> visibleTools(List<ToolMetadata> tools, ToolAudience audience) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream().filter(tool -> visibleTo(tool, audience)).toList();
    }

    public boolean requiresApproval(ToolMetadata tool) {
        return tool != null
                && (tool.risk() == ToolRiskLevel.HIGH || tool.approval() != ToolApprovalPolicy.NONE);
    }
}
