package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.contract.tool.ToolParameterDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CityCopilotMcpToolCallbackProviderTest {

    @Test
    void exposesCoreCatalogAsSpringAiMcpCallbacks() {
        McpToolService service = mock(McpToolService.class);
        when(service.catalog()).thenReturn(List.of(new ToolDefinition(
                "get_shop_detail",
                "Get shop detail",
                List.of(),
                60,
                true,
                List.of(new ToolParameterDefinition("shopId", "java.lang.Long", true))
        )));
        when(service.call(any())).thenReturn(ToolInvocationResponse.success("shop-3"));
        ToolCallbackProvider provider = new CityCopilotMcpToolCallbackProvider()
                .cityCopilotToolCallbackProvider(service);

        ToolCallback[] callbacks = provider.getToolCallbacks();

        assertEquals(1, callbacks.length);
        assertEquals("get_shop_detail", callbacks[0].getToolDefinition().name());
        assertTrue(callbacks[0].getToolDefinition().inputSchema().contains("shopId"));
        assertTrue(callbacks[0].call("{\"shopId\":3}").contains("shop-3"));
    }
}
