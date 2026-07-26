package com.citycopilot.contract.tool;

import java.util.List;
import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        List<String> requiredPermissions,
        int rateLimitPerMinute,
        boolean idempotent,
        List<ToolParameterDefinition> parameters,
        ToolAudience audience,
        ToolSideEffect sideEffect,
        ToolRiskLevel risk,
        ToolApprovalPolicy approval,
        String scope,
        int contractVersion,
        Map<String, Object> resultSchema
) {
    public ToolDefinition {
        requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        audience = audience == null ? ToolAudience.ALL : audience;
        sideEffect = sideEffect == null ? ToolSideEffect.NONE : sideEffect;
        risk = risk == null ? ToolRiskLevel.LOW : risk;
        approval = approval == null ? ToolApprovalPolicy.NONE : approval;
        scope = scope == null || scope.isBlank() ? "core" : scope;
        contractVersion = Math.max(1, contractVersion);
        resultSchema = resultSchema == null ? Map.of() : Map.copyOf(resultSchema);
    }

    public ToolDefinition(
            String name,
            String description,
            List<String> requiredPermissions,
            int rateLimitPerMinute,
            boolean idempotent,
            List<ToolParameterDefinition> parameters
    ) {
        this(
                name,
                description,
                requiredPermissions,
                rateLimitPerMinute,
                idempotent,
                parameters,
                ToolAudience.ALL,
                ToolSideEffect.NONE,
                ToolRiskLevel.LOW,
                ToolApprovalPolicy.NONE,
                "core",
                1,
                Map.of()
        );
    }
}
