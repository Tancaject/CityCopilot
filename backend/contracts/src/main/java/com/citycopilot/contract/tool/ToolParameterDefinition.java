package com.citycopilot.contract.tool;

import java.util.Map;

/**
 * 单个工具参数的协议描述，用于生成模型可理解的参数 Schema。
 *
 * @param name 参数名
 * @param javaType 声明该参数的 Java 类型名
 * @param required 协议层是否要求调用方提供该参数
 * @param schema 参数对应的 JSON Schema 片段
 */
public record ToolParameterDefinition(
        String name,
        String javaType,
        boolean required,
        Map<String, Object> schema
) {
    /** 将缺失 Schema 规范化为空对象，并对传入数据做防御性复制。 */
    public ToolParameterDefinition {
        schema = schema == null ? Map.of() : Map.copyOf(schema);
    }

    /**
     * 创建没有额外 Schema 约束的参数定义。
     *
     * @param name 参数名
     * @param javaType Java 类型名
     * @param required 是否必填
     */
    public ToolParameterDefinition(String name, String javaType, boolean required) {
        this(name, javaType, required, Map.of());
    }
}
