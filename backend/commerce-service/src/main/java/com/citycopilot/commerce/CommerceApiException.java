package com.citycopilot.commerce;

/**
 * Commerce HTTP 接口的业务异常，统一携带稳定错误码和目标 HTTP 状态码。
 *
 * <p>控制层不在各端点重复拼装错误响应，而由 {@link CommerceExceptionAdvice} 集中转换。</p>
 */
public class CommerceApiException extends RuntimeException {
    /** 供调用方做分支判断的机器可读错误码。 */
    private final String code;
    /** 返回给调用方的 HTTP 状态码。 */
    private final int status;

    /**
     * 创建一个可映射为 HTTP 响应的业务异常。
     *
     * @param code 稳定的业务错误码
     * @param message 面向调用方的错误说明
     * @param status HTTP 状态码
     */
    public CommerceApiException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public int status() { return status; }
}
