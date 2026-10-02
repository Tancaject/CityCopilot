package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolParameterDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把共享工具合同转换为 MCP tools/list 所需的名称、描述与 JSON Schema。 */
public final class McpToolMapper {
    private McpToolMapper() {
    }

    /**
     * @param definitions Core 返回的工具定义
     * @return 保持目录顺序的 MCP 工具描述列表
     */
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

    /**
     * 根据参数合同生成严格对象 Schema，并把未声明字段标记为不允许。
     *
     * @param tool 单个工具定义
     * @return MCP inputSchema 对象
     */
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

    /**
     * 把常见 Java 标量类型映射为 JSON Schema 基础类型。
     *
     * @param javaType 参数合同中的 Java 类型名
     * @return JSON Schema 类型；未知类型保守映射为 object
     */
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
