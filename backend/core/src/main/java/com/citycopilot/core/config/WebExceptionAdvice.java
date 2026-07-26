package com.citycopilot.core.config;

import com.citycopilot.core.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import com.citycopilot.core.security.ShopAccessDeniedException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(ShopAccessDeniedException.class)
    public ResponseEntity<Result> handleShopAccessDenied(ShopAccessDeniedException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail("SHOP_ACCESS_DENIED", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.fail("BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("SERVER_ERROR", "服务器异常");
    }
}
