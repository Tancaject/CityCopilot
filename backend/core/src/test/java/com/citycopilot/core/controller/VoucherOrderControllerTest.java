package com.citycopilot.core.controller;

import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoucherOrderControllerTest {
    private final IVoucherOrderService orders = mock(IVoucherOrderService.class);
    private final VoucherOrderController controller = new VoucherOrderController(orders);

    @Test
    void returns202ForNewAdmission() {
        Result accepted = Result.ok("300");
        accepted.setCode("COMMERCE_ORDER_ACCEPTED");
        when(orders.seckillVoucher(10L, "request-1")).thenReturn(accepted);

        ResponseEntity<Result> response = controller.seckillVoucher(10L, "request-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create("/voucher-order/300"));
        assertThat(response.getBody()).isSameAs(accepted);
    }

    @Test
    void returns200ForIdempotentReplay() {
        Result replay = Result.ok("300");
        replay.setCode("IDEMPOTENT_REPLAY");
        when(orders.seckillVoucher(10L, "request-1")).thenReturn(replay);

        ResponseEntity<Result> response = controller.seckillVoucher(10L, "request-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create("/voucher-order/300"));
        assertThat(response.getBody()).isSameAs(replay);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMMERCE_ADMISSION_OUTCOME_UNKNOWN",
            "COMMERCE_DURABLE_ADMISSION_UNAVAILABLE",
            "COMMERCE_ROUTING_MUTATION_LEASE_LOST"
    })
    void exposesRetryableUnknownOutcomes(String code) {
        CommercePortClient.CommerceHttpException error = new CommercePortClient.CommerceHttpException(
                503,
                code,
                "Retry with the same Idempotency-Key",
                "1",
                null
        );

        ResponseEntity<Result> response = controller.commerceFailure(error);

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

        ResponseEntity<Result> response = controller.commerceFailure(error);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("SECKILL_STOCK_NOT_ENOUGH");
        assertThat(response.getBody().getErrorMsg()).isEqualTo("Voucher stock exhausted");
    }
}
