package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingContracts.MutationResult;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Order(-1)
@RestControllerAdvice(basePackageClasses = BookingController.class)
public class BookingExceptionAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<MutationResult> invalidRequest(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(new MutationResult("BOOKING_REQUEST_REJECTED",
                error.getStatusCode().value(), error.getReason(), null));
    }
    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<byte[]> downstream(RestClientResponseException error) {
        var response = ResponseEntity.status(error.getStatusCode()).contentType(MediaType.APPLICATION_JSON);
        String retryAfter = error.getResponseHeaders() == null ? null : error.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null) response.header("Retry-After", retryAfter);
        return response.body(error.getResponseBodyAsByteArray());
    }

    @ExceptionHandler(BookingPortClient.Unavailable.class)
    ResponseEntity<MutationResult> unavailable(BookingPortClient.Unavailable error) {
        return ResponseEntity.status(503).header("Retry-After", "1").body(new MutationResult(
                error.outcomeUnknown ? "BOOKING_OUTCOME_UNKNOWN" : "BOOKING_SERVICE_UNAVAILABLE", 503,
                error.outcomeUnknown ? "预约服务连接中断，操作结果尚未确定。请查询原操作结果，重试时使用同一操作键。"
                        : "预约服务暂时无法连接，请稍后刷新。", null));
    }
}
