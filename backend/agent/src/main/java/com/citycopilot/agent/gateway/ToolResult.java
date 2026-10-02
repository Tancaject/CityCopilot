package com.citycopilot.agent.gateway;

/**
 * 工具网关的统一返回值，确保执行器无需依赖 Core 异常类型。
 *
 * @param ok 调用是否成功
 * @param data 成功时的业务数据
 * @param error 失败时的标准错误
 */
public record ToolResult(boolean ok, Object data, ToolError error) {
    /** @return 携带业务数据的成功结果 */
    public static ToolResult success(Object data) {
        return new ToolResult(true, data, null);
    }

    /** @return 不携带 data 的标准失败结果 */
    public static ToolResult failure(String code, String message, boolean retryable) {
        return new ToolResult(false, null, new ToolError(code, message, retryable));
    }
}
