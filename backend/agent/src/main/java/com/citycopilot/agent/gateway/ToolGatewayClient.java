package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.ToolMetadata;

import java.util.List;
import java.util.Map;

public interface ToolGatewayClient {
    List<ToolMetadata> tools();

    ToolResult invoke(String name, Map<String, Object> args, ToolCallContext context);
}
