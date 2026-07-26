package com.citycopilot.agent.gateway;

import com.citycopilot.agent.config.CoreServiceProperties;
import com.citycopilot.agent.remote.CoreServiceCallException;
import com.citycopilot.agent.remote.CoreServiceClient;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteToolGatewayTest {

    @Test
    void returnsControlledErrorWhenCoreIsUnavailable() {
        CoreServiceClient client = mock(CoreServiceClient.class);
        when(client.tools(any())).thenReturn(List.of(writeTool()));
        when(client.invoke(anyString(), any(), any())).thenThrow(
                new CoreServiceCallException("core_unavailable", "Core unavailable", true, null)
        );
        RemoteToolGateway gateway = new RemoteToolGateway(client, ToolExecutionGuard.direct(), properties());

        ToolResult result = gateway.invoke(
                "write_demo",
                Map.of("value", "x"),
                new ToolCallContext("42", Set.of("order:write"), "Bearer token", "request-1", "stable-key")
        );

        assertFalse(result.ok());
        assertEquals("core_unavailable", result.error().code());
        assertTrue(result.error().retryable());
    }

    @Test
    void onlyMarksWritesRetrySafeWhenStableKeyExists() {
        CoreServiceClient client = mock(CoreServiceClient.class);
        when(client.tools(any())).thenReturn(List.of(writeTool()));
        when(client.invoke(anyString(), any(), any())).thenReturn(ToolInvocationResponse.success("ok"));
        AtomicBoolean observedRetrySafe = new AtomicBoolean();
        ToolExecutionGuard guard = (tool, invocation) -> {
            observedRetrySafe.set(tool.idempotent());
            return invocation.call();
        };
        RemoteToolGateway gateway = new RemoteToolGateway(client, guard, properties());

        gateway.invoke("write_demo", Map.of(), new ToolCallContext("42", Set.of()));
        assertFalse(observedRetrySafe.get());

        gateway.invoke(
                "write_demo",
                Map.of(),
                new ToolCallContext("42", Set.of(), "Bearer token", "request-1", "stable-key")
        );
        assertTrue(observedRetrySafe.get());
    }

    private ToolDefinition writeTool() {
        return new ToolDefinition("write_demo", "write", List.of("order:write"), 60, false, List.of());
    }

    private CoreServiceProperties properties() {
        CoreServiceProperties properties = new CoreServiceProperties();
        properties.setCatalogTtl(Duration.ofMinutes(1));
        return properties;
    }
}
