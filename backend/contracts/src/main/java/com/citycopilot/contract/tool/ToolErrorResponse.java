package com.citycopilot.contract.tool;

/**
 * 工具调用失败时返回的稳定错误结构。
 *
 * @param code 供程序分支判断的错误码
 * @param message 供用户或日志阅读的错误说明
 * @param retryable 调用方是否可以在保持幂等语义的前提下重试
 */
public record ToolErrorResponse(String code, String message, boolean retryable) {
}
