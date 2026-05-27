package com.citycopilot.mcp;

import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.core.agent.AgentTool;

import java.util.List;
import java.util.Map;

public final class McpToolMapperSelfTest {

    public static void main(String[] args) {
        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new ShopTools()));
        List<Map<String, Object>> tools = McpToolMapper.toToolsList(registry.tools());

        assertEquals(1, tools.size(), "tool count");
        assertEquals("get_shop_detail", tools.get(0).get("name"), "tool name");
        assertEquals("Get shop detail", tools.get(0).get("description"), "description");
        assertEquals("object", ((Map<?, ?>) tools.get(0).get("inputSchema")).get("type"), "schema type");
    }

    static final class ShopTools {
        @AgentTool(name = "get_shop_detail", description = "Get shop detail")
        public String detail(Long id) {
            return "shop-" + id;
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
