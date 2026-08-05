package com.citycopilot.commerce;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class CommerceExceptionAdvice {
    private static final Set<String> RETRYABLE_OUTCOME_CODES = Set.of(
            "COMMERCE_ADMISSION_OUTCOME_UNKNOWN",
            "COMMERCE_DURABLE_ADMISSION_UNAVAILABLE",
            "COMMERCE_ROUTING_MUTATION_LEASE_LOST"
    );

    @ExceptionHandler(CommerceApiException.class)
    ResponseEntity<Map<String, Object>> handle(CommerceApiException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
        if (RETRYABLE_OUTCOME_CODES.contains(error.code())) {
            response.header("Retry-After", "1");
        }
        return response.body(Map.of(
                "success", false,
                "code", error.code(),
                "message", error.getMessage()
        ));
    }
}
