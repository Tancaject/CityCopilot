package com.citycopilot.agent.tool;

import com.citycopilot.core.agent.AgentTool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentToolRegistry {
    private final Map<String, ToolMetadata> tools;

    private AgentToolRegistry(Map<String, ToolMetadata> tools) {
        this.tools = Map.copyOf(tools);
    }

    public static AgentToolRegistry fromBeans(List<?> beans) {
        Map<String, ToolMetadata> discovered = new LinkedHashMap<>();
        for (Object bean : beans) {
            registerBean(discovered, bean);
        }
        return new AgentToolRegistry(discovered);
    }

    public List<ToolMetadata> tools() {
        return List.copyOf(tools.values());
    }

    public ToolMetadata require(String name) {
        ToolMetadata tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown agent tool: " + name);
        }
        return tool;
    }

    private static void registerBean(Map<String, ToolMetadata> discovered, Object bean) {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            AgentTool annotation = findAnnotation(bean.getClass(), method);
            if (annotation == null) {
                continue;
            }
            method.setAccessible(true);
            ToolMetadata metadata = toMetadata(bean, method, annotation);
            ToolMetadata existing = discovered.putIfAbsent(metadata.name(), metadata);
            if (existing != null) {
                throw new IllegalStateException("Duplicate agent tool name: " + metadata.name());
            }
        }
    }

    private static AgentTool findAnnotation(Class<?> beanType, Method method) {
        AgentTool direct = method.getAnnotation(AgentTool.class);
        if (direct != null) {
            return direct;
        }
        for (Class<?> contract : beanType.getInterfaces()) {
            AgentTool fromContract = findInterfaceAnnotation(contract, method);
            if (fromContract != null) {
                return fromContract;
            }
        }
        return null;
    }

    private static AgentTool findInterfaceAnnotation(Class<?> contract, Method implementationMethod) {
        try {
            Method contractMethod = contract.getMethod(
                    implementationMethod.getName(),
                    implementationMethod.getParameterTypes()
            );
            AgentTool annotation = contractMethod.getAnnotation(AgentTool.class);
            if (annotation != null) {
                return annotation;
            }
        } catch (NoSuchMethodException ignored) {
            // Keep walking parent interfaces below.
        }
        for (Class<?> parent : contract.getInterfaces()) {
            AgentTool fromParent = findInterfaceAnnotation(parent, implementationMethod);
            if (fromParent != null) {
                return fromParent;
            }
        }
        return null;
    }

    private static ToolMetadata toMetadata(Object bean, Method method, AgentTool annotation) {
        return new ToolMetadata(
                annotation.name(),
                annotation.description(),
                List.of(annotation.requiredPermissions()),
                annotation.rateLimitPerMinute(),
                annotation.idempotent(),
                bean,
                bean.getClass(),
                method,
                parametersOf(method)
        );
    }

    private static List<ToolParameter> parametersOf(Method method) {
        List<ToolParameter> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            parameters.add(new ToolParameter(parameter.getName(), parameter.getType(), true));
        }
        return parameters;
    }
}
