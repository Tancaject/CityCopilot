package com.citycopilot.agent.tool;

import java.util.Map;

/**
 * 单个工具参数的运行时元数据。
 *
 * @param name 模型 schema 与网关命名参数使用的名称
 * @param javaType 反射调用目标类型
 * @param required 调用前是否必须存在
 */
public record ToolParameter(String name, Class<?> javaType, boolean required, Map<String, Object> schema) {
    public ToolParameter(String name, Class<?> javaType, boolean required) {
        this(name, javaType, required, Map.of());
    }
    public ToolParameter { schema = schema == null ? Map.of() : Map.copyOf(schema); }
}
