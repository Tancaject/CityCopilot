package com.citycopilot.mcp;

import com.citycopilot.agent.gateway.ToolAuditSink;
import com.citycopilot.agent.gateway.ToolGateway;
import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.security.PermissionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityCopilotMcpToolCallbackProviderTest {

    @Test
    void exposesRegistryToolsAsSpringAiMcpCallbacks() {
        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new DemoTools()));
        ToolGateway gateway = new ToolGateway(registry, ToolAuditSink.noop());
        ToolCallbackProvider provider = new CityCopilotMcpToolCallbackProvider()
                .cityCopilotToolCallbackProvider(registry, gateway, new PermissionPolicy());

        ToolCallback[] callbacks = provider.getToolCallbacks();

        assertEquals(1, callbacks.length);
        assertEquals("get_shop_detail", callbacks[0].getToolDefinition().name());
        assertTrue(callbacks[0].getToolDefinition().inputSchema().contains("shopId"));
        assertTrue(callbacks[0].call("{\"shopId\":3}").contains("shop-3"));
    }

    static final class DemoTools {
        @AgentTool(name = "get_shop_detail", description = "Get shop detail")
        public String detail(Long shopId) {
            return "shop-" + shopId;
        }
    }
}
