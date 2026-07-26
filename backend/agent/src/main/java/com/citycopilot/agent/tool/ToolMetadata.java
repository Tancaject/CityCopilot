package com.citycopilot.agent.tool;

import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolApprovalPolicy;

import java.lang.reflect.Method;
import java.util.List;

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
        String scope
) {
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
                                parameter.required()
                        ))
                        .toList(),
                definition.audience(),
                definition.sideEffect(),
                definition.risk(),
                definition.approval(),
                definition.scope()
        );
    }

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
                scope
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
