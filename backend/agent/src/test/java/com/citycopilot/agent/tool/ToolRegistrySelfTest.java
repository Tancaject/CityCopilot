package com.citycopilot.agent.tool;

import com.citycopilot.contract.tool.AgentTool;

import java.util.List;

public final class ToolRegistrySelfTest {

    public static void main(String[] args) {
        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new DemoTools(), new ContractToolsImpl()));

        assertEquals(2, registry.tools().size(), "registered tool count");

        ToolMetadata tool = registry.require("search_nearby_shops");
        assertEquals("search_nearby_shops", tool.name(), "tool name");
        assertEquals("Search nearby shops", tool.description(), "tool description");
        assertEquals(List.of("shop:read"), tool.requiredPermissions(), "required permissions");
        assertEquals(10, tool.rateLimitPerMinute(), "rate limit");
        assertEquals(true, tool.idempotent(), "idempotency");
        assertEquals(DemoTools.class, tool.beanType(), "bean type");
        assertEquals("search", tool.method().getName(), "method name");
        assertEquals(2, tool.parameters().size(), "parameter count");
        assertEquals(String.class, tool.parameters().get(0).javaType(), "first parameter type");
        assertEquals(Integer.class, tool.parameters().get(1).javaType(), "second parameter type");

        ToolMetadata interfaceTool = registry.require("get_shop_detail");
        assertEquals("Get shop detail", interfaceTool.description(), "interface tool description");
        assertEquals(ContractToolsImpl.class, interfaceTool.beanType(), "interface tool bean type");
        assertEquals(Long.class, interfaceTool.parameters().get(0).javaType(), "interface parameter type");
    }

    static final class DemoTools {
        @AgentTool(
                name = "search_nearby_shops",
                description = "Search nearby shops",
                requiredPermissions = {"shop:read"},
                rateLimitPerMinute = 10,
                idempotent = true
        )
        public String search(String keyword, Integer maxPrice) {
            return keyword + maxPrice;
        }
    }

    interface ContractTools {
        @AgentTool(name = "get_shop_detail", description = "Get shop detail")
        String detail(Long id);
    }

    static final class ContractToolsImpl implements ContractTools {
        @Override
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
