package com.citycopilot.core.config;

import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.ShopAccessDeniedException;
import com.citycopilot.core.utils.CacheRebuildBusyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 把 Core 常见业务与运行时异常转换为统一 {@link Result} 响应，并让失败响应带上对应的 HTTP 状态。
 *
 * <p>服务层仍以 {@code Result.fail(code, ...)} 表达业务失败；控制器没有显式设置状态时，
 * 这里按错误码约定把 200 改成 4xx/5xx，响应体保持不变。</p>
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result result && Boolean.FALSE.equals(result.getSuccess())
                && response instanceof ServletServerHttpResponse servlet
                && servlet.getServletResponse().getStatus() == HttpStatus.OK.value()) {
            response.setStatusCode(statusFor(result.getCode()));
        }
        return body;
    }

    /**
     * 错误码到 HTTP 状态的约定：后缀表达失败类别，未命中的按客户端请求错误处理。
     *
     * @param code 稳定错误码
     * @return 对应 HTTP 状态
     */
    static HttpStatus statusFor(String code) {
        String c = code == null ? "" : code;
        if (c.equals("AUTH_REQUIRED")) return HttpStatus.UNAUTHORIZED;
        if (c.endsWith("ACCESS_DENIED") || c.endsWith("FORBIDDEN")) return HttpStatus.FORBIDDEN;
        if (c.endsWith("NOT_FOUND")) return HttpStatus.NOT_FOUND;
        if (c.endsWith("RATE_LIMITED")) return HttpStatus.TOO_MANY_REQUESTS;
        if (c.endsWith("UNAVAILABLE") || c.endsWith("CIRCUIT_OPEN") || c.endsWith("BUSY")
                || c.endsWith("CONFIG_MISSING")) return HttpStatus.SERVICE_UNAVAILABLE;
        if (c.endsWith("RESPONSE_INVALID") || c.endsWith("INVALID_RESPONSE")) return HttpStatus.BAD_GATEWAY;
        if (c.equals("SERVER_ERROR") || c.endsWith("_FAILED")) return HttpStatus.INTERNAL_SERVER_ERROR;
        return HttpStatus.BAD_REQUEST;
    }

    /**
     * 保留 Commerce 上游状态码及 {@code Retry-After}，让限流或暂时不可用时的客户端退避语义不丢失。
     *
     * @param error Commerce 端口调用异常
     * @return 保留重试提示的统一失败响应
     */
    @ExceptionHandler(CommercePortClient.CommerceHttpException.class)
    public ResponseEntity<Result> handleCommerceFailure(CommercePortClient.CommerceHttpException error) {
        if (error.status() >= 500) {
            // 结果未知等服务端失败需要根因才能排查；4xx 是正常业务拒绝，不记日志。
            log.warn("Commerce call failed with {} {}", error.status(), error.code(), error.getCause());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
        if (error.retryAfter() != null && !error.retryAfter().isBlank()) {
            response.header(HttpHeaders.RETRY_AFTER, error.retryAfter());
        }
        return response.body(Result.fail(error.code(), error.getMessage()));
    }

    /**
     * @param error 店铺归属校验异常
     * @return 商户越权修改店铺时的 403 响应
     */
    @ExceptionHandler(ShopAccessDeniedException.class)
    public ResponseEntity<Result> handleShopAccessDenied(ShopAccessDeniedException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail("SHOP_ACCESS_DENIED", error.getMessage()));
    }

    /**
     * 缓存正在由其他请求重建时返回 503，并提示客户端一秒后重试。
     *
     * @param error 缓存重建繁忙异常
     * @return 带 Retry-After 的临时不可用响应
     */
    @ExceptionHandler(CacheRebuildBusyException.class)
    public ResponseEntity<Result> handleCacheRebuildBusy(CacheRebuildBusyException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(Result.fail("CACHE_REBUILD_BUSY", error.getMessage()));
    }

    /**
     * @param e 参数校验异常
     * @return 参数合同不满足时的 BAD_REQUEST 失败响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.fail("BAD_REQUEST", e.getMessage());
    }

    /**
     * 记录未预期运行时异常的堆栈，并对外隐藏内部细节。
     *
     * @param e 未预期运行时异常
     * @return 通用 SERVER_ERROR 响应
     */
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("SERVER_ERROR", "服务器异常");
    }
}
