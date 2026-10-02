package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.AdmissionAccepted;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceControllerAdmissionTest {

    @Test
    void newlyAcceptedAdmissionUsesHttp202() {
        CommerceOrderAdmissionService orders = mock(CommerceOrderAdmissionService.class);
        CommerceRoutingGuard routing = mock(CommerceRoutingGuard.class);
        CommerceController controller = controller(orders, routing);
        AdmissionAccepted body = new AdmissionAccepted("300", "processing", "COMMERCE_ORDER_ACCEPTED", "request-1");
        when(orders.seckill(100L, 200L, "request-1")).thenReturn(body);

        ResponseEntity<AdmissionAccepted> response =
                controller.seckill(100L, 200L, "request-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(URI.create("/commerce/v1/orders/300"));
        assertThat(response.getBody()).isEqualTo(body);
        verify(routing).requireAdmissionActive(100L);
    }

    @Test
    void idempotentReplayUsesHttp200() {
        CommerceOrderAdmissionService orders = mock(CommerceOrderAdmissionService.class);
        CommerceRoutingGuard routing = mock(CommerceRoutingGuard.class);
        CommerceController controller = controller(orders, routing);
        AdmissionAccepted body = new AdmissionAccepted("300", "processing", "IDEMPOTENT_REPLAY", "request-1");
        when(orders.seckill(100L, 200L, "request-1")).thenReturn(body);

        ResponseEntity<AdmissionAccepted> response =
                controller.seckill(100L, 200L, "request-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(URI.create("/commerce/v1/orders/300"));
        assertThat(response.getBody()).isEqualTo(body);
    }

    private CommerceController controller(
            CommerceOrderAdmissionService orders,
            CommerceRoutingGuard routing
    ) {
        return new CommerceController(
                mock(CommerceOrderService.class),
                orders,
                mock(CommerceVoucherService.class),
                mock(CommerceConsistencyService.class),
                mock(CommerceOperationsService.class),
                routing
        );
    }
}
