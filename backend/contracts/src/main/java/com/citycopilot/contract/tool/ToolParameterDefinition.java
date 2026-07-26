package com.citycopilot.contract.tool;

import java.util.Map;

public record ToolParameterDefinition(
        String name,
        String javaType,
        boolean required,
        Map<String, Object> schema
) {
    public ToolParameterDefinition {
        schema = schema == null ? Map.of() : Map.copyOf(schema);
    }

    public ToolParameterDefinition(String name, String javaType, boolean required) {
        this(name, javaType, required, Map.of());
    }
}
