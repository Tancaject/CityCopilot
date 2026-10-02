package com.citycopilot.mcp;

import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolServiceTest {

    @Test
    void listsOnlyToolsAuthorizedByCoreSession() {
        McpCoreClient client = mock(McpCoreClient.class);
        when(client.session(any())).thenReturn(
                new ServiceUser(9L, "merchant", null, "merchant", Set.of("order:write"), true)
        );
        when(client.tools(any())).thenReturn(List.of(
                tool("echo", List.of()),
                tool("pay_order", List.of("order:write")),
                tool("admin_only", List.of("admin:access"))
        ));
        McpToolService service = new McpToolService(client, new McpCoreProperties());

        List<Map<String, Object>> tools = service.toolsList();

        assertTrue(tools.stream().anyMatch(tool -> "echo".equals(tool.get("name"))));
        assertTrue(tools.stream().anyMatch(tool -> "pay_order".equals(tool.get("name"))));
        assertTrue(tools.stream().noneMatch(tool -> "admin_only".equals(tool.get("name"))));
    }

    @Test
    void ignoresCallerSuppliedIdentityAndDelegatesAuthorizationToCore() {
        McpCoreClient client = mock(McpCoreClient.class);
        when(client.tools(any())).thenReturn(List.of(tool("pay_order", List.of(), 3)));
        when(client.invoke(anyString(), any(), any(), anyInt())).thenReturn(
                ToolInvocationResponse.failure("permission_denied", "denied", false)
        );
        McpToolService service = new McpToolService(client, new McpCoreProperties());

        ToolInvocationResponse result = service.call(new McpToolCallRequest(
                "pay_order", Map.of("orderId", 123L), "spoofed", Set.of("*")
        ));

        assertEquals("permission_denied", result.error().code());
        verify(client).invoke(eq("pay_order"), any(), any(), eq(1));
    }

    @Test
    void sdkInvocationKeepsPublishedVersionWhenCoreCatalogChanges() {
        McpCoreClient client = mock(McpCoreClient.class);
        when(client.tools(any())).thenReturn(List.of(tool("pay_order", List.of(), 2)));
        when(client.invoke(anyString(), any(), any(), anyInt())).thenReturn(
                ToolInvocationResponse.failure("tool_contract_mismatch", "Review the changed contract", false));
        McpToolService service = new McpToolService(client, new McpCoreProperties());
        var published = new CityCopilotMcpToolCallbackProvider()
                .cityCopilotToolCallbackProvider(service).getToolCallbacks()[0];
        when(client.tools(any())).thenReturn(List.of(tool("pay_order", List.of(), 3)));

        assertTrue(published.call("{\"orderId\":123}").contains("tool_contract_mismatch"));
        verify(client).invoke(eq("pay_order"), eq(Map.of("orderId", 123)), any(), eq(2));
    }

    private ToolDefinition tool(String name, List<String> permissions) {
        return tool(name, permissions, 1);
    }

    private ToolDefinition tool(String name, List<String> permissions, int version) {
        return new ToolDefinition(name, name, permissions, 60, true, List.of(),
                null, null, null, null, "core", version, Map.of());
    }
}
