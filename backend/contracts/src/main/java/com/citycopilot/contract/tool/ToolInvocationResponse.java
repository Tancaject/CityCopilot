package com.citycopilot.contract.tool;

public record ToolInvocationResponse(boolean ok, Object data, ToolErrorResponse error) {
    public static ToolInvocationResponse success(Object data) {
        return new ToolInvocationResponse(true, data, null);
    }

    public static ToolInvocationResponse failure(String code, String message, boolean retryable) {
        return new ToolInvocationResponse(false, null, new ToolErrorResponse(code, message, retryable));
    }
}
