package com.citycopilot.agent.gateway;

public record ToolError(String code, String message, boolean retryable) {
}
