package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供简化的 HTTP 工具目录和调用接口。
 *
 * <p>控制器不信任请求体内的用户与权限提示，实际可见性和执行授权由服务层及 Core 判断。</p>
 */
@RestController
@RequestMapping("/mcp/tools")
public class McpToolController {
    /** 负责工具过滤、Schema 映射和 Core 转发的应用服务。 */
    private final McpToolService toolService;

    public McpToolController(McpToolService toolService) {
        this.toolService = toolService;
    }

    /** @return 当前身份可见的 MCP 工具列表 */
    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        return toolService.toolsList();
    }

    /**
     * @param request 工具名、实参与审阅的 expectedVersion；旧请求缺失版本时仅按版本 1 执行
     * @return Core 返回的结构化成功或失败结果
     */
    @PostMapping("/call")
    public ToolInvocationResponse call(@RequestBody McpToolCallRequest request) {
        return toolService.call(request);
    }
}
