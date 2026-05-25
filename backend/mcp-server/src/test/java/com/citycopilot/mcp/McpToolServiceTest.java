package com.citycopilot.mcp;

import com.citycopilot.agent.gateway.ToolAuditSink;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolServiceTest {

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void listsAndCallsToolsThroughSharedGateway() {
        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new DemoTools()));
        McpToolService service = new McpToolService(
                registry,
                new ToolGateway(registry, ToolAuditSink.noop()),
                new PermissionPolicy()
        );

        assertTrue(service.toolsList().stream().anyMatch(tool -> "echo".equals(tool.get("name"))));

        Object result = service.call(new McpToolCallRequest(
                "echo",
                Map.of("text", "city"),
                "mcp-user",
                Set.of()
        )).data();

        assertEquals("city", result);
    }

    @Test
    void ignoresCallerSuppliedPermissions() {
        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new DemoTools()));
        McpToolService service = new McpToolService(
                registry,
                new ToolGateway(registry, ToolAuditSink.noop()),
                new PermissionPolicy()
        );

        ToolResult result = service.call(new McpToolCallRequest(
                "pay_order",
                Map.of("orderId", 123L),
                "victim",
                Set.of("*", "order:write")
        ));

        assertEquals("permission_denied", result.error().code());
    }

    @Test
    void usesAuthenticatedUserPermissionsFromServerContext() {
        UserDTO user = new UserDTO();
        user.setId(9L);
        user.setRole("merchant");
        UserHolder.saveUser(user);

        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new DemoTools()));
        McpToolService service = new McpToolService(
                registry,
                new ToolGateway(registry, ToolAuditSink.noop()),
                new PermissionPolicy()
        );

        ToolResult result = service.call(new McpToolCallRequest(
                "pay_order",
                Map.of("orderId", 123L),
                "spoofed",
                Set.of()
        ));

        assertEquals("paid-123", result.data());
    }

    static final class DemoTools {
        @AgentTool(name = "echo", description = "Echo text")
        public String echo(String text) {
            return text;
        }

        @AgentTool(name = "pay_order", description = "Pay order", requiredPermissions = {"order:write"})
        public String pay(Long orderId) {
            return "paid-" + orderId;
        }
    }
}
