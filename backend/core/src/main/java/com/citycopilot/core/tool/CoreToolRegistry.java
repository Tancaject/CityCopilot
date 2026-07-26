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

@Component
public class CoreToolRegistry implements SmartInitializingSingleton {
    private final ApplicationContext applicationContext;
    private volatile Map<String, CoreToolBinding> bindings = Map.of();

    public CoreToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

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

    public List<ToolDefinition> definitions() {
        return bindings.values().stream().map(CoreToolBinding::definition).toList();
    }

    CoreToolBinding require(String name) {
        CoreToolBinding binding = bindings.get(name);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown agent tool: " + name);
        }
        return binding;
    }

    private void registerBean(Map<String, CoreToolBinding> discovered, Object bean) {
        Map<MethodSignature, AnnotatedMethod> annotatedMethods = new LinkedHashMap<>();
        collectAnnotatedMethods(AopUtils.getTargetClass(bean), annotatedMethods);
        collectAnnotatedMethods(bean.getClass(), annotatedMethods);
        for (AnnotatedMethod annotated : annotatedMethods.values()) {
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

    private Map<String, Object> schemaFor(Type type) {
        if (type == null) {
            return Map.of("type", "object");
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

    private record AnnotatedMethod(Method method, AgentTool annotation) {
    }

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        static MethodSignature of(Method method) {
            return new MethodSignature(method.getName(), Arrays.asList(method.getParameterTypes()));
        }
    }
}
