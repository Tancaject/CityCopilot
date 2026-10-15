package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IVoucherOrderService;
import org.junit.jupiter.api.Test;
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
}
