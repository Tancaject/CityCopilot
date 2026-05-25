package com.citycopilot.agent.schema;

import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonSchemaGenerator {

    private JsonSchemaGenerator() {
    }

    public static Map<String, Object> parametersSchema(ToolMetadata tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ToolParameter parameter : tool.parameters()) {
            properties.put(parameter.name(), Map.of("type", jsonType(parameter.javaType())));
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
