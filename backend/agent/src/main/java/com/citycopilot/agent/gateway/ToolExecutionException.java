package com.citycopilot.agent.gateway;

public class ToolExecutionException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public ToolExecutionException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
