package com.citycopilot.agent.remote;

/**
 * Agent 调用 Core 内部接口时的标准跨服务异常。
 *
 * <p>RemoteToolGateway 将其继续转换为工具错误，保留稳定错误码和重试语义。</p>
 */
public final class CoreServiceCallException extends RuntimeException {
    /** HTTP 或连接失败对应的稳定错误码。 */
    private final String code;
    /** 是否属于可安全重试的基础设施失败。 */
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
