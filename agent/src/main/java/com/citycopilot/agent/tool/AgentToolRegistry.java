package com.citycopilot.agent.tool;

import com.citycopilot.core.agent.AgentTool;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
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
        if (bean == null) {
            return;
        }
        Map<MethodSignature, AnnotatedMethod> annotatedMethods = new LinkedHashMap<>();
        collectAnnotatedMethods(AopUtils.getTargetClass(bean), annotatedMethods);
        collectAnnotatedMethods(bean.getClass(), annotatedMethods);
        for (AnnotatedMethod annotatedMethod : annotatedMethods.values()) {
            Method method = AopUtils.selectInvocableMethod(annotatedMethod.method(), bean.getClass());
            method.setAccessible(true);
            ToolMetadata metadata = toMetadata(bean, method, annotatedMethod.annotation());
            ToolMetadata existing = discovered.putIfAbsent(metadata.name(), metadata);
            if (existing != null) {
                throw new IllegalStateException("Duplicate agent tool name: " + metadata.name());
            }
        }
    }

    private static void collectAnnotatedMethods(Class<?> type, Map<MethodSignature, AnnotatedMethod> methods) {
        if (type == null || type == Object.class) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            if (annotation != null) {
                methods.putIfAbsent(MethodSignature.of(method), new AnnotatedMethod(method, annotation));
            }
        }
        for (Class<?> contract : type.getInterfaces()) {
            collectAnnotatedMethods(contract, methods);
        }
        collectAnnotatedMethods(type.getSuperclass(), methods);
    }

    private static ToolMetadata toMetadata(Object bean, Method method, AgentTool annotation) {
        return new ToolMetadata(
                annotation.name(),
                annotation.description(),
                List.of(annotation.requiredPermissions()),
                annotation.rateLimitPerMinute(),
                annotation.idempotent(),
                bean,
                method.getDeclaringClass(),
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

    private record AnnotatedMethod(Method method, AgentTool annotation) {
    }

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        static MethodSignature of(Method method) {
            return new MethodSignature(method.getName(), Arrays.asList(method.getParameterTypes()));
        }
    }
}
