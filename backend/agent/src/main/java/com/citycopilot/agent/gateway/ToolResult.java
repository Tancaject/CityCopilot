package com.citycopilot.agent.gateway;

public record ToolResult(boolean ok, Object data, ToolError error) {
    public static ToolResult success(Object data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult failure(String code, String message, boolean retryable) {
        return new ToolResult(false, null, new ToolError(code, message, retryable));
    }
}
