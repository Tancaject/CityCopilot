package com.citycopilot.commerce;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class CommerceExceptionAdvice {
    @ExceptionHandler(CommerceApiException.class)
    ResponseEntity<Map<String, Object>> handle(CommerceApiException error) {
        return ResponseEntity.status(error.status()).body(Map.of(
                "success", false,
                "code", error.code(),
                "message", error.getMessage()
        ));
    }
}
