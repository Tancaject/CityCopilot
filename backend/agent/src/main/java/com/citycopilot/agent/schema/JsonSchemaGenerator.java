package com.citycopilot.agent.schema;

import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将内部工具参数元数据转换为 Function Calling JSON Schema。
 *
 * <p>OpenAI 兼容客户端复用此生成器，避免 prompt 描述与 ToolGateway 参数合同漂移。</p>
 */
public final class JsonSchemaGenerator {

    private JsonSchemaGenerator() {
    }

    /**
     * 生成对象类型的参数 schema。
     *
     * @param tool 工具元数据
     * @return 含 properties 与 required 数组的 JSON 兼容 Map
     */
    public static Map<String, Object> parametersSchema(ToolMetadata tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParameter parameter : tool.parameters()) {
            properties.put(parameter.name(), parameter.schema().isEmpty()
                    ? Map.of("type", jsonType(parameter.javaType())) : parameter.schema());
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static String jsonType(Class<?> javaType) {
        if (javaType == String.class || javaType == Character.class || javaType == char.class) {
            return "string";
        }
        if (javaType == Integer.class || javaType == int.class
                || javaType == Long.class || javaType == long.class
                || javaType == Short.class || javaType == short.class
                || javaType == Byte.class || javaType == byte.class) {
            return "integer";
        }
        if (javaType == Double.class || javaType == double.class
                || javaType == Float.class || javaType == float.class) {
            return "number";
        }
        if (javaType == Boolean.class || javaType == boolean.class) {
            return "boolean";
        }
        return "object";
    }
}
