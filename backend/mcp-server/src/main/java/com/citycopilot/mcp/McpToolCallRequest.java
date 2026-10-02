package com.citycopilot.mcp;

import java.util.Map;
import java.util.Set;

/**
 * MCP HTTP 兼容接口接收的工具调用载荷。
 *
 * @param name 稳定工具名
 * @param arguments 按名称组织的工具实参
 * @param userId 旧客户端携带的用户标识；授权身份仍由 Core 从请求上下文解析
 * @param permissions 旧客户端携带的权限提示；不能替代 Core 的权限校验
 * @param expectedVersion 客户端审阅的目录契约版本；缺失仅表示旧协议版本 1
 */
public record McpToolCallRequest(
        String name,
        Map<String, Object> arguments,
        String userId,
        Set<String> permissions,
        Integer expectedVersion
) {
    public McpToolCallRequest(String name, Map<String, Object> arguments, String userId, Set<String> permissions) {
        this(name, arguments, userId, permissions, null);
    }
}
