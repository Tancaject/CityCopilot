package com.citycopilot.contract.tool;

/**
 * 工具调用的互斥成功/失败结果。
 *
 * @param ok 调用是否成功
 * @param data 成功时的业务结果
 * @param error 失败时的结构化错误
 */
public record ToolInvocationResponse(boolean ok, Object data, ToolErrorResponse error) {
    /**
     * 创建成功响应。
     *
     * @param data 工具返回的业务数据
     * @return 不包含错误信息的成功响应
     */
    public static ToolInvocationResponse success(Object data) {
        return new ToolInvocationResponse(true, data, null);
    }

    /**
     * 创建失败响应。
     *
     * @param code 稳定错误码
     * @param message 错误说明
     * @param retryable 是否允许调用方重试
     * @return 不包含业务数据的失败响应
     */
    public static ToolInvocationResponse failure(String code, String message, boolean retryable) {
        return new ToolInvocationResponse(false, null, new ToolErrorResponse(code, message, retryable));
    }
}
