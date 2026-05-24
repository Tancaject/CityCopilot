package com.citycopilot.mcp;

import com.citycopilot.agent.schema.JsonSchemaGenerator;
import com.citycopilot.agent.tool.ToolMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpToolMapper {

    private McpToolMapper() {
    }

    public static List<Map<String, Object>> toToolsList(List<ToolMetadata> metadata) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolMetadata tool : metadata) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("inputSchema", JsonSchemaGenerator.parametersSchema(tool));
            tools.add(entry);
        }
        return tools;
    }
}
