package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.contract.tool.AgentTool;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ToolGatewaySelfTest {

    public static void main(String[] args) {
        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new OrderTools()));
        ToolGateway gateway = new ToolGateway(registry);

        ToolResult denied = gateway.invoke(
                "place_seckill_order",
                Map.of("voucherId", 7L),
                new ToolCallContext("user-1", Set.of())
        );
        assertEquals(false, denied.ok(), "denied ok");
        assertEquals("permission_denied", denied.error().code(), "denied code");

        ToolResult allowed = gateway.invoke(
                "place_seckill_order",
                Map.of("voucherId", 7L),
                new ToolCallContext("user-1", Set.of("order:write"))
        );
        assertEquals(true, allowed.ok(), "allowed ok");
        assertEquals("order-7", allowed.data(), "allowed data");
    }

    static final class OrderTools {
        @AgentTool(
                name = "place_seckill_order",
                description = "Place order",
                requiredPermissions = {"order:write"},
                idempotent = false
        )
        public String place(Long voucherId) {
            return "order-" + voucherId;
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
