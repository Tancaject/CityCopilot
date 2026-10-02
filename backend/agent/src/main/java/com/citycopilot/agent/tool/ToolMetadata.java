package com.citycopilot.agent.tool;

import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolApprovalPolicy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具的统一运行时元数据。
 *
 * @param name 全局唯一工具名
 * @param description 提供给模型与管理端的说明
 * @param requiredPermissions 调用者必须同时具备的权限
 * @param rateLimitPerMinute 分钟级限流额度
 * @param idempotent 是否允许保护层自动重试
 * @param bean 本地工具实例；远程目录中为空
 * @param beanType 本地声明类型；远程目录中为空
 * @param method 本地反射方法；远程目录中为空
 * @param parameters 参数合同
 * @param audience 可见受众
 * @param sideEffect 副作用类型
 * @param risk 风险级别
 * @param approval 审批策略
 * @param scope 工具所属服务范围
 * @param contractVersion Core 声明的工具契约版本
 * @param resultSchema 成功结果的声明形状
 */
public record ToolMetadata(
        String name,
        String description,
        List<String> requiredPermissions,
        int rateLimitPerMinute,
        boolean idempotent,
        Object bean,
        Class<?> beanType,
        Method method,
        List<ToolParameter> parameters,
        ToolAudience audience,
        ToolSideEffect sideEffect,
        ToolRiskLevel risk,
        ToolApprovalPolicy approval,
        String scope,
        int contractVersion,
        Map<String, Object> resultSchema
) {
    public ToolMetadata {
        if (contractVersion < 1) throw new IllegalArgumentException("Tool contract version must be positive");
        resultSchema = new ToolContractSnapshot(contractVersion, resultSchema).resultSchema();
    }

    public ToolMetadata(String name, String description, List<String> requiredPermissions, int rateLimitPerMinute,
                        boolean idempotent, Object bean, Class<?> beanType, Method method, List<ToolParameter> parameters,
                        ToolAudience audience, ToolSideEffect sideEffect, ToolRiskLevel risk,
                        ToolApprovalPolicy approval, String scope) {
        this(name, description, requiredPermissions, rateLimitPerMinute, idempotent, bean, beanType, method,
                parameters, audience, sideEffect, risk, approval, scope, 1, Map.of());
    }
    public ToolMetadata(
            String name,
            String description,
            List<String> requiredPermissions,
            int rateLimitPerMinute,
            boolean idempotent,
            Object bean,
            Class<?> beanType,
            Method method,
            List<ToolParameter> parameters
    ) {
        this(
                name,
                description,
                requiredPermissions,
                rateLimitPerMinute,
                idempotent,
                bean,
                beanType,
                method,
                parameters,
                ToolAudience.ALL,
                ToolSideEffect.NONE,
                ToolRiskLevel.LOW,
                ToolApprovalPolicy.NONE,
                "core"
        );
    }

    /**
     * 将 Core 传输工具定义转换为 Agent 运行时元数据。
     *
     * <p>远程工具没有本地 bean/method，字符串类型尽力映射为 Java Class 以生成 schema。</p>
     */
    public static ToolMetadata fromDefinition(ToolDefinition definition) {
        return new ToolMetadata(
                definition.name(),
                definition.description(),
                definition.requiredPermissions(),
                definition.rateLimitPerMinute(),
                definition.idempotent(),
                null,
                null,
                null,
                definition.parameters().stream()
                        .map(parameter -> new ToolParameter(
                                parameter.name(),
                                javaType(parameter.javaType()),
                                parameter.required(),
                                parameter.schema()
                        ))
                        .toList(),
                definition.audience(),
                definition.sideEffect(),
                definition.risk(),
                definition.approval(),
                definition.scope(),
                definition.contractVersion(),
                definition.resultSchema()
        );
    }

    /**
     * @return 标记为可重试的元数据副本；仅当调用链另有幂等键保护时使用
     */
    public ToolMetadata asRetrySafe() {
        if (idempotent) {
            return this;
        }
        return new ToolMetadata(
                name,
                description,
                requiredPermissions,
                rateLimitPerMinute,
                true,
                bean,
                beanType,
                method,
                parameters,
                audience,
                sideEffect,
                risk,
                approval,
                scope,
                contractVersion,
                resultSchema
        );
    }

    private static Class<?> javaType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return Object.class;
        }
        return switch (typeName) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            default -> classForName(typeName);
        };
    }

    private static Class<?> classForName(String typeName) {
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException ignored) {
            return Object.class;
        }
    }
}
