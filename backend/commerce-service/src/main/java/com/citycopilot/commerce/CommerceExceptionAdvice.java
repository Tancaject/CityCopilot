package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceAdmissionOutcomePolicy;
import com.citycopilot.contract.commerce.CommerceContracts.CommerceError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将 Commerce 业务异常转换为统一 JSON 错误结构，并补充必要的重试提示。 */
@RestControllerAdvice
public class CommerceExceptionAdvice {
    /**
     * 将 {@link CommerceApiException} 的错误码、消息和状态码原样映射到响应。
     *
     * @param error 业务层抛出的已分类异常
     * @return 统一的失败响应
     */
    @ExceptionHandler(CommerceApiException.class)
    ResponseEntity<CommerceError> handle(CommerceApiException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
        // Retry-After 只提供退避时间；写请求是否可重试仍取决于其幂等键和结果查询协议。
        if (CommerceAdmissionOutcomePolicy.isRetryable(error.code())) {
            response.header("Retry-After", "1");
        }
        return response.body(new CommerceError(false, error.code(), error.getMessage()));
    }
}
