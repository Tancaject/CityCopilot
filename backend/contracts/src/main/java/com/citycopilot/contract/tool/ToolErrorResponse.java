package com.citycopilot.contract.tool;

public record ToolErrorResponse(String code, String message, boolean retryable) {
}
