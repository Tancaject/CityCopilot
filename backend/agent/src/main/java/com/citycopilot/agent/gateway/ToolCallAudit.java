package com.citycopilot.agent.gateway;

import java.util.Map;

/**
 * 单次工具调用的审计快照。
 *
 * @param userId 发起调用的用户
 * @param toolName 工具注册名
 * @param args 调用参数副本
 * @param success 是否成功
 * @param error 失败详情
 * @param durationMs 调用耗时毫秒数
 */
public record ToolCallAudit(
        String userId,
        String toolName,
        Map<String, Object> args,
        boolean success,
        ToolError error,
        long durationMs
) {
}
