package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceControllerAdmissionTest {

    @Test
    void newlyAcceptedAdmissionUsesHttp202() {
        CommerceOrderService orders = mock(CommerceOrderService.class);
        CommerceRoutingGuard routing = mock(CommerceRoutingGuard.class);
        CommerceController controller = controller(orders, routing);
        Map<String, Object> body = Map.of(
                "orderId", "300",
                "state", "processing",
                "code", "COMMERCE_ORDER_ACCEPTED",
                "idempotencyKey", "request-1"
        );
        when(orders.seckill(100L, 200L, "request-1")).thenReturn(body);

        ResponseEntity<Map<String, Object>> response =
                controller.seckill(100L, 200L, "request-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(URI.create("/commerce/v1/orders/300"));
        assertThat(response.getBody()).isEqualTo(body);
        verify(routing).requireAdmissionActive(100L);
    }

    @Test
    void idempotentReplayUsesHttp200() {
        CommerceOrderService orders = mock(CommerceOrderService.class);
        CommerceRoutingGuard routing = mock(CommerceRoutingGuard.class);
        CommerceController controller = controller(orders, routing);
        Map<String, Object> body = Map.of(
                "orderId", "300",
                "state", "processing",
                "code", "IDEMPOTENT_REPLAY",
                "idempotencyKey", "request-1"
        );
        when(orders.seckill(100L, 200L, "request-1")).thenReturn(body);

        ResponseEntity<Map<String, Object>> response =
                controller.seckill(100L, 200L, "request-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(URI.create("/commerce/v1/orders/300"));
        assertThat(response.getBody()).isEqualTo(body);
    }

    private CommerceController controller(
            CommerceOrderService orders,
            CommerceRoutingGuard routing
    ) {
        return new CommerceController(
                orders,
                mock(CommerceVoucherService.class),
                mock(CommerceConsistencyService.class),
                mock(CommerceOperationsService.class),
                routing
        );
    }
}
