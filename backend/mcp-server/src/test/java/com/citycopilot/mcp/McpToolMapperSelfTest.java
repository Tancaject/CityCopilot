package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolParameterDefinition;

import java.util.List;
import java.util.Map;

public final class McpToolMapperSelfTest {
    public static void main(String[] args) {
        ToolDefinition definition = new ToolDefinition(
                "get_shop_detail",
                "Get shop detail",
                List.of(),
                60,
                true,
                List.of(new ToolParameterDefinition("id", "java.lang.Long", true))
        );
        List<Map<String, Object>> tools = McpToolMapper.toToolsList(List.of(definition));

        assertEquals(1, tools.size(), "tool count");
        assertEquals("get_shop_detail", tools.get(0).get("name"), "tool name");
        assertEquals("object", ((Map<?, ?>) tools.get(0).get("inputSchema")).get("type"), "schema type");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
