package com.citycopilot.agent.remote;

public final class CoreServiceCallException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public CoreServiceCallException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
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
