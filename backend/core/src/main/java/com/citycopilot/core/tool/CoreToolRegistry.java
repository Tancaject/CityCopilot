package com.citycopilot.core.tool;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolParameterDefinition;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core Agent 工具注册表。
 *
 * <p>Spring 单例初始化完成后扫描 Bean、接口和父类上的 {@link AgentTool}，生成工具定义并绑定可调用方法。</p>
 */
@Component
public class CoreToolRegistry implements SmartInitializingSingleton {

    /** Spring 容器，用于发现 Bean 类型和取得代理实例。 */
    private final ApplicationContext applicationContext;

    /** 按工具名保存的不可变运行时快照；volatile 保证初始化后对请求线程可见。 */
    private volatile Map<String, CoreToolBinding> bindings = Map.of();

    /** @param applicationContext Spring 应用上下文 */
    public CoreToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 扫描全部已创建单例并原子发布工具绑定快照。
     *
     * @throws IllegalStateException 发现重复工具名时抛出，避免调用路由不确定
     */
    @Override
    public void afterSingletonsInstantiated() {
        Map<String, CoreToolBinding> discovered = new LinkedHashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null || !hasAgentTool(beanType)) {
                continue;
            }
            registerBean(discovered, applicationContext.getBean(beanName));
        }
        bindings = Map.copyOf(discovered);
    }

    /** @return 当前全部工具合同，供 MCP/Agent 获取工具目录 */
    public List<ToolDefinition> definitions() {
        return bindings.values().stream().map(CoreToolBinding::definition).toList();
    }

    /**
     * 按工具名取得运行时绑定。
     *
     * @param name {@link AgentTool#name()} 声明的稳定名称
     * @return 工具绑定
     * @throws IllegalArgumentException 工具不存在时抛出
     */
    CoreToolBinding require(String name) {
        CoreToolBinding binding = bindings.get(name);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown agent tool: " + name);
        }
        return binding;
    }

    /**
     * 收集一个 Bean 的注解方法并注册代理可调用版本。
     *
     * @param discovered 当前扫描结果
     * @param bean Spring Bean，可能是 AOP 代理
     */
    private void registerBean(Map<String, CoreToolBinding> discovered, Object bean) {
        Map<MethodSignature, AnnotatedMethod> annotatedMethods = new LinkedHashMap<>();
        collectAnnotatedMethods(AopUtils.getTargetClass(bean), annotatedMethods);
        collectAnnotatedMethods(bean.getClass(), annotatedMethods);
        for (AnnotatedMethod annotated : annotatedMethods.values()) {
            // selectInvocableMethod 把目标类方法解析为当前代理对象真正可调用的方法。
            Method method = AopUtils.selectInvocableMethod(annotated.method(), bean.getClass());
            method.setAccessible(true);
            ToolDefinition definition = definition(method, annotated.annotation());
            CoreToolBinding previous = discovered.putIfAbsent(
                    definition.name(),
                    new CoreToolBinding(definition, bean, method)
            );
            if (previous != null) {
                throw new IllegalStateException("Duplicate agent tool name: " + definition.name());
            }
        }
    }

    /**
     * 递归判断类型、接口或父类是否声明了 Agent 工具。
     *
     * @param type 待检查类型
     * @return 是否至少存在一个工具注解
     */
    private boolean hasAgentTool(Class<?> type) {
        if (type == null || type == Object.class) {
            return false;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(AgentTool.class)) {
                return true;
            }
        }
        for (Class<?> contract : type.getInterfaces()) {
            if (hasAgentTool(contract)) {
                return true;
            }
        }
        return hasAgentTool(type.getSuperclass());
    }

    /**
     * 递归收集类层次中的注解方法，并按方法签名去除接口与实现重复项。
     *
     * @param type 当前扫描类型
     * @param methods 已收集方法
     */
    private void collectAnnotatedMethods(Class<?> type, Map<MethodSignature, AnnotatedMethod> methods) {
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

    /**
     * 把方法注解和反射参数转换为跨模块工具合同。
     *
     * @param method 工具方法
     * @param annotation 工具元数据注解
     * @return 可序列化工具定义
     */
    private ToolDefinition definition(Method method, AgentTool annotation) {
        Set<String> optionalParameters = Set.of(annotation.optionalParameters());
        List<ToolParameterDefinition> parameters = Arrays.stream(method.getParameters())
                .map(parameter -> parameterDefinition(parameter, optionalParameters))
                .toList();
        return new ToolDefinition(
                annotation.name(),
                annotation.description(),
                List.of(annotation.requiredPermissions()),
                annotation.rateLimitPerMinute(),
                annotation.idempotent(),
                parameters,
                annotation.audience(),
                annotation.sideEffect(),
                annotation.risk(),
                annotation.approval(),
                annotation.scope(),
                annotation.contractVersion(),
                schemaFor(method.getGenericReturnType())
        );
    }

    /**
     * 根据参数名、Java 类型和可选参数声明构建参数合同。
     *
     * @param parameter 反射参数
     * @param optionalParameters 注解显式声明的可选参数名
     * @return 工具参数定义
     */
    private ToolParameterDefinition parameterDefinition(Parameter parameter, Set<String> optionalParameters) {
        String name = parameter.getName();
        boolean required = !optionalParameters.contains(name)
                && (parameter.getType().isPrimitive()
                || name.equalsIgnoreCase("id")
                || name.toLowerCase().endsWith("id")
                || name.equalsIgnoreCase("address"));
        return new ToolParameterDefinition(
                parameter.getName(),
                parameter.getType().getName(),
                required,
                schemaFor(parameter.getParameterizedType())
        );
    }

    /**
     * 将 Java 类型映射为轻量 JSON Schema，同时保留原始 Java 类型便于诊断。
     *
     * @param type Java 泛型类型
     * @return 至少含 {@code type} 和 {@code javaType} 的 Schema
     */
    private Map<String, Object> schemaFor(Type type) {
        if (type == null) {
            return Map.of("type", "object");
        }
        if (type instanceof Class<?> record && record.isRecord()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (var component : record.getRecordComponents()) properties.put(component.getName(), schemaFor(component.getGenericType()));
            return Map.of("type", "object", "javaType", type.getTypeName(), "properties", properties);
        }
        if (type == java.time.Instant.class || type == java.time.OffsetDateTime.class || type == java.util.Date.class) {
            return Map.of("type", "string", "format", "date-time", "javaType", type.getTypeName());
        }
        if (type == java.time.LocalDate.class) {
            return Map.of("type", "string", "format", "date", "javaType", type.getTypeName());
        }
        if (type == java.time.LocalTime.class) {
            return Map.of("type", "string", "pattern", "^[0-9]{2}:[0-9]{2}(:[0-9]{2}(\\.[0-9]{1,9})?)?$", "javaType", type.getTypeName());
        }
        if (type instanceof java.lang.reflect.ParameterizedType generic
                && (generic.getRawType() == List.class || generic.getRawType() == Set.class)) {
            return Map.of("type", "array", "items", schemaFor(generic.getActualTypeArguments()[0]), "javaType", type.getTypeName());
        }
        String typeName = type.getTypeName();
        String jsonType;
        if (typeName.equals("boolean") || typeName.equals(Boolean.class.getName())) {
            jsonType = "boolean";
        } else if (typeName.matches("(byte|short|int|long|java\\.lang\\.(Byte|Short|Integer|Long))")) {
            jsonType = "integer";
        } else if (typeName.matches("(float|double|java\\.lang\\.(Float|Double)|java\\.math\\.BigDecimal)")) {
            jsonType = "number";
        } else if (typeName.equals(String.class.getName())
                || typeName.equals("char")
                || typeName.equals(Character.class.getName())) {
            jsonType = "string";
        } else if (typeName.startsWith("java.util.List")
                || typeName.startsWith("java.util.Set")
                || typeName.endsWith("[]")) {
            jsonType = "array";
        } else {
            jsonType = "object";
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", jsonType);
        schema.put("javaType", typeName);
        return Map.copyOf(schema);
    }

    /**
     * 扫描阶段保存的方法及其注解。
     *
     * @param method 被注解方法
     * @param annotation 工具注解
     */
    private record AnnotatedMethod(Method method, AgentTool annotation) {
    }

    /**
     * 用方法名和参数类型标识重载，避免接口与实现被重复注册。
     *
     * @param name 方法名
     * @param parameterTypes 参数类型序列
     */
    private record MethodSignature(String name, List<Class<?>> parameterTypes) {

        /**
         * @param method 反射方法
         * @return 对应方法签名
         */
        static MethodSignature of(Method method) {
            return new MethodSignature(method.getName(), Arrays.asList(method.getParameterTypes()));
        }
    }
}
