package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.CommerceError;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceExceptionAdviceTest {
    private final CommerceExceptionAdvice advice = new CommerceExceptionAdvice();

    @Test
    void unknownAdmissionOutcomeIncludesRetryAfter() {
        ResponseEntity<CommerceError> response = advice.handle(
                new CommerceApiException(
                        "COMMERCE_ADMISSION_OUTCOME_UNKNOWN",
                        "Retry with the same Idempotency-Key",
                        503
                )
        );

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody().code()).isEqualTo("COMMERCE_ADMISSION_OUTCOME_UNKNOWN");
    }

    @Test
    void stableBusinessRejectionHasNoRetryAfter() {
        ResponseEntity<CommerceError> response = advice.handle(
                new CommerceApiException("SECKILL_STOCK_NOT_ENOUGH", "Sold out", 409)
        );

        assertThat(response.getHeaders().containsHeader("Retry-After")).isFalse();
    }
}
