package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.contract.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGatewayResilienceTest {

    @Test
    void appliesToolLevelRateLimit() {
        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(new DemoTools()));
        ToolGateway gateway = new ToolGateway(
                registry,
                ToolAuditSink.noop(),
                new Resilience4jToolExecutionGuard()
        );

        ToolResult first = gateway.invoke("limited_echo", Map.of("text", "a"), new ToolCallContext("u1", Set.of()));
        ToolResult second = gateway.invoke("limited_echo", Map.of("text", "b"), new ToolCallContext("u1", Set.of()));

        assertTrue(first.ok());
        assertFalse(second.ok());
        assertEquals("rate_limited", second.error().code());
    }

    static final class DemoTools {
        @AgentTool(name = "limited_echo", description = "Echo", rateLimitPerMinute = 1)
        public String echo(String text) {
            return text;
        }
    }
}
