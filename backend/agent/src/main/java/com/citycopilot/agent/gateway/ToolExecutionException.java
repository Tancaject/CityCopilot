package com.citycopilot.agent.gateway;

/**
 * 工具保护层抛出的可分类执行异常。
 *
 * <p>网关捕获后会转换为 {@link ToolError}，保留错误码和是否可重试语义。</p>
 */
public class ToolExecutionException extends RuntimeException {
    /** 稳定机器错误码。 */
    private final String code;
    /** 是否允许执行器在安全边界内再次尝试。 */
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
