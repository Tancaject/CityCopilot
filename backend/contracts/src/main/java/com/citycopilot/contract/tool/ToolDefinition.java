package com.citycopilot.contract.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具扫描器产出的完整工具定义，供 Agent、MCP 与 Core 在同一合同上协作。
 *
 * @param name 稳定工具名
 * @param description 提供给模型或客户端的能力说明
 * @param requiredPermissions 调用所需权限码
 * @param rateLimitPerMinute 接入侧针对该工具执行的分钟限流值
 * @param idempotent 工具自身是否能安全重复执行
 * @param parameters 工具入参定义
 * @param audience 可使用工具的客户端范围
 * @param sideEffect 对系统状态的影响类型
 * @param risk 业务风险等级
 * @param approval 执行前审批策略
 * @param scope 工具所属服务或能力域
 * @param contractVersion 合同版本
 * @param resultSchema 工具成功结果的 JSON Schema
 */
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
    /**
     * 防御性复制集合，并为旧调用方缺失的治理元数据补充安全默认值。
     */
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

    /**
     * 构造兼容旧合同的基础工具定义，其余治理属性使用默认值。
     *
     * @param name 稳定工具名
     * @param description 能力说明
     * @param requiredPermissions 调用所需权限码
     * @param rateLimitPerMinute 接入侧分钟限流值
     * @param idempotent 工具自身是否能安全重复执行
     * @param parameters 工具入参定义
     */
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
