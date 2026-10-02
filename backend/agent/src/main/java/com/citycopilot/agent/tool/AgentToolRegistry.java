package com.citycopilot.agent.tool;

import com.citycopilot.contract.tool.AgentTool;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描 {@link AgentTool} 注解并建立本地工具注册表。
 *
 * <p>兼容 Spring AOP 代理、接口和继承声明，解析可调用方法与参数合同；重复工具名在启动期直接失败，避免模型调用到不确定实现。</p>
 */
public final class AgentToolRegistry {
    /** 全局工具名到不可变元数据的映射。 */
    private final Map<String, ToolMetadata> tools;

    private AgentToolRegistry(Map<String, ToolMetadata> tools) {
        this.tools = Map.copyOf(tools);
    }

    /**
     * 从候选 bean 集合构建注册表。
     *
     * @throws IllegalStateException 发现重复工具名
     */
    public static AgentToolRegistry fromBeans(List<?> beans) {
        Map<String, ToolMetadata> discovered = new LinkedHashMap<>();
        for (Object bean : beans) {
            registerBean(discovered, bean);
        }
        return new AgentToolRegistry(discovered);
    }

    /** @return 按发现顺序排列的工具元数据不可变列表 */
    public List<ToolMetadata> tools() {
        return List.copyOf(tools.values());
    }

    /** @return 指定工具元数据；未知名称时抛出参数异常 */
    public ToolMetadata require(String name) {
        ToolMetadata tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown agent tool: " + name);
        }
        return tool;
    }

    /** 解析代理目标类与代理类，并选择对实际 bean 可反射调用的方法。 */
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

    /** 递归收集类、接口和父类上的注解方法，并按签名去重。 */
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

    /** 将注解声明、反射方法和参数推导合并成统一工具元数据。 */
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
                parametersOf(method, Set.of(annotation.optionalParameters())),
                annotation.audience(),
                annotation.sideEffect(),
                annotation.risk(),
                annotation.approval(),
                annotation.scope(),
                annotation.contractVersion(),
                Map.of()
        );
    }

    /**
     * 推导参数必填性：显式可选项除外，基本类型、标识和地址默认必填。
     */
    private static List<ToolParameter> parametersOf(Method method, Set<String> optionalParameters) {
        List<ToolParameter> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            String name = parameter.getName();
            boolean required = !optionalParameters.contains(name)
                    && (parameter.getType().isPrimitive()
                    || name.equalsIgnoreCase("id")
                    || name.toLowerCase().endsWith("id")
                    || name.equalsIgnoreCase("address"));
            parameters.add(new ToolParameter(name, parameter.getType(), required));
        }
        return parameters;
    }

    /** 绑定原始声明方法与其工具注解。 */
    private record AnnotatedMethod(Method method, AgentTool annotation) {
    }

    /** 跨接口/代理去重方法所用的名称与参数类型签名。 */
    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        static MethodSignature of(Method method) {
            return new MethodSignature(method.getName(), Arrays.asList(method.getParameterTypes()));
        }
    }
}
