package com.citycopilot.agent.gateway;

/**
 * 工具失败的稳定错误合同。
 *
 * @param code 供执行器与前端判断的机器码
 * @param message 面向日志或用户的简短说明
 * @param retryable 是否允许在幂等保护下重试或重规划
 */
public record ToolError(String code, String message, boolean retryable) {
}
