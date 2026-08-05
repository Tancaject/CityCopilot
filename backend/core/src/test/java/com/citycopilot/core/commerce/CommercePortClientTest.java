package com.citycopilot.core.commerce;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CommercePortClientTest {
    private MockRestServiceServer server;
    private CommercePortClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://commerce.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CommercePortClient(builder.build(), "service-secret");
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "202, COMMERCE_ORDER_ACCEPTED",
            "200, IDEMPOTENT_REPLAY"
    })
    void preservesSuccessfulAdmissionCode(int status, String code) {
        expectSeckill().andRespond(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"success":true,"code":"%s","orderId":300,"idempotencyKey":"request-1"}
                        """.formatted(code)));

        Result result = client.seckill(10L, "request-1");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCode()).isEqualTo(code);
        assertThat(result.getData()).isEqualTo("300");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMMERCE_ADMISSION_OUTCOME_UNKNOWN",
            "COMMERCE_DURABLE_ADMISSION_UNAVAILABLE",
            "COMMERCE_ROUTING_MUTATION_LEASE_LOST"
    })
    void exposesRetryableAdmissionFailures(String code) {
        expectSeckill().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"success":false,"code":"%s","message":"Admission could not be confirmed"}
                        """.formatted(code)));

        CommercePortClient.CommerceHttpException error = catchThrowableOfType(
                () -> client.seckill(10L, "request-1"),
                CommercePortClient.CommerceHttpException.class
        );

        assertThat(error.status()).isEqualTo(503);
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.retryAfter()).isEqualTo("1");
        assertThat(error.getMessage()).contains("same Idempotency-Key");
    }

    @Test
    void preservesStableRejectionStatusAndCode() {
        expectSeckill().andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"success":false,"code":"SECKILL_STOCK_NOT_ENOUGH","message":"Voucher stock exhausted"}
                        """));

        CommercePortClient.CommerceHttpException error = catchThrowableOfType(
                () -> client.seckill(10L, "request-1"),
                CommercePortClient.CommerceHttpException.class
        );

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("SECKILL_STOCK_NOT_ENOUGH");
        assertThat(error.getMessage()).isEqualTo("Voucher stock exhausted");
        assertThat(error.retryAfter()).isNull();
    }

    @Test
    void treatsTransportFailureAsUnknownAdmissionOutcome() {
        expectSeckill().andRespond(request -> {
            throw new IOException("connection reset");
        });

        CommercePortClient.CommerceHttpException error = catchThrowableOfType(
                () -> client.seckill(10L, "request-1"),
                CommercePortClient.CommerceHttpException.class
        );

        assertThat(error.status()).isEqualTo(503);
        assertThat(error.code()).isEqualTo("COMMERCE_ADMISSION_OUTCOME_UNKNOWN");
        assertThat(error.retryAfter()).isEqualTo("1");
        assertThat(error.getMessage()).contains("same Idempotency-Key");
    }

    private org.springframework.test.web.client.ResponseActions expectSeckill() {
        return server.expect(requestTo("http://commerce.test/commerce/v1/seckill/10"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-CityCopilot-Service-Token", "service-secret"))
                .andExpect(header("X-CityCopilot-User-Id", "7"))
                .andExpect(header("Idempotency-Key", "request-1"));
    }
}
