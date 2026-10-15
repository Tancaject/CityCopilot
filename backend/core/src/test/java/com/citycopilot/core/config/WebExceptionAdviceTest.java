package com.citycopilot.core.config;

import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.utils.CacheRebuildBusyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class WebExceptionAdviceTest {

    @Test
    void cacheRebuildContentionReturnsRetryableServiceUnavailable() {
        WebExceptionAdvice advice = new WebExceptionAdvice();

        ResponseEntity<Result> response = advice.handleCacheRebuildBusy(
                new CacheRebuildBusyException("店铺缓存正在重建，请稍后重试")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("CACHE_REBUILD_BUSY");
    }

    @ParameterizedTest
    @CsvSource({
            "AUTH_REQUIRED,401", "SHOP_ACCESS_DENIED,403", "SHOP_NOT_FOUND,404",
            "LOGIN_CODE_RATE_LIMITED,429", "COMMERCE_UNAVAILABLE,503", "AMAP_RESPONSE_INVALID,502",
            "SERVER_ERROR,500", "LOGIN_CODE_INVALID,400", "BAD_REQUEST,400"
    })
    void failureCodesMapToHttpStatus(String code, int status) {
        assertThat(WebExceptionAdvice.statusFor(code).value()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMMERCE_ADMISSION_OUTCOME_UNKNOWN",
            "COMMERCE_DURABLE_ADMISSION_UNAVAILABLE"
    })
    void exposesRetryableUnknownOutcomes(String code) {
        CommercePortClient.CommerceHttpException error = new CommercePortClient.CommerceHttpException(
                503,
                code,
                "Retry with the same Idempotency-Key",
                "1",
                null
        );

        ResponseEntity<Result> response = new WebExceptionAdvice().handleCommerceFailure(error);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuccess()).isFalse();
        assertThat(response.getBody().getCode()).isEqualTo(code);
        assertThat(response.getBody().getErrorMsg()).contains("same Idempotency-Key");
    }

    @Test
    void preservesNonRetryableFailureStatusWithoutRetryHeader() {
        CommercePortClient.CommerceHttpException error = new CommercePortClient.CommerceHttpException(
                409,
                "SECKILL_STOCK_NOT_ENOUGH",
                "Voucher stock exhausted",
                null,
                null
        );

        ResponseEntity<Result> response = new WebExceptionAdvice().handleCommerceFailure(error);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("SECKILL_STOCK_NOT_ENOUGH");
        assertThat(response.getBody().getErrorMsg()).isEqualTo("Voucher stock exhausted");
    }
}
