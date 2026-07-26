package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolParameterDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpToolMapper {
    private McpToolMapper() {
    }

    public static List<Map<String, Object>> toToolsList(List<ToolDefinition> definitions) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition tool : definitions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("inputSchema", parametersSchema(tool));
            entry.put("outputSchema", tool.resultSchema());
            entry.put("contractVersion", tool.contractVersion());
            tools.add(entry);
        }
        return tools;
    }

    public static Map<String, Object> parametersSchema(ToolDefinition tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameterDefinition parameter : tool.parameters()) {
            properties.put(
                    parameter.name(),
                    parameter.schema().isEmpty()
                            ? Map.of("type", jsonType(parameter.javaType()))
                            : parameter.schema()
            );
            if (parameter.required()) required.add(parameter.name());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static String jsonType(String javaType) {
        if (javaType == null) return "object";
        return switch (javaType) {
            case "byte", "short", "int", "long", "java.lang.Byte", "java.lang.Short",
                    "java.lang.Integer", "java.lang.Long" -> "integer";
            case "float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> "number";
            case "boolean", "java.lang.Boolean" -> "boolean";
            case "java.lang.String", "char", "java.lang.Character" -> "string";
            default -> "object";
        };
    }
}
