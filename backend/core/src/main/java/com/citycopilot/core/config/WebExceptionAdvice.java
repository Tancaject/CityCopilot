package com.citycopilot.core.config;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.ShopAccessDeniedException;
import com.citycopilot.core.utils.CacheRebuildBusyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 把 Core 常见业务与运行时异常转换为统一 {@link Result} 响应。 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

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
