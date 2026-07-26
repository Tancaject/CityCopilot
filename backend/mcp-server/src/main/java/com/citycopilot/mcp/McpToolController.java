package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mcp/tools")
public class McpToolController {
    private final McpToolService toolService;

    public McpToolController(McpToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        return toolService.toolsList();
    }

    @PostMapping("/call")
    public ToolInvocationResponse call(@RequestBody McpToolCallRequest request) {
        return toolService.call(request);
    }
}
