package com.citycopilot.core.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import com.citycopilot.contract.commerce.CommerceContracts.OrderSummary;
import com.citycopilot.contract.commerce.CommerceContracts.OrderView;
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
import com.citycopilot.core.config.CrossServiceHttpConfig;
import com.citycopilot.http.RequestDeadline;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
                        {"state":"processing","code":"%s","orderId":"9007199254740993","idempotencyKey":"request-1","additionalInfo":{}}
                        """.formatted(code)));

        Result result = client.seckill(10L, "request-1");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCode()).isEqualTo(code);
        assertThat(result.getData()).isEqualTo("9007199254740993");
    }

    @Test
    void sendsTypedActionAndRepairWithoutChangingWireFields() {
        server.expect(requestTo("http://commerce.test/commerce/v1/orders/300/actions?admin=false&merchant=false"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"action":"pay","payType":1}
                        """))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"id":"300","userId":7,"voucherId":10,"shopId":20,"status":2,"version":1,
                                 "shopName":"店铺","voucherTitle":"双人券","payValue":9900,"actualValue":12000,
                                 "createTime":"2026-09-25T00:00:00.123+00:00","updateTime":"2026-09-25T00:00:00.123+00:00"}
                                """));
        server.expect(requestTo("http://commerce.test/commerce/v1/consistency/10/repair"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-CityCopilot-Service-Token", "service-secret"))
                .andExpect(header("X-CityCopilot-User-Id", "7"))
                .andExpect(content().json("""
                        {"expectedEvidenceHash":"hash-before"}
                        """))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"voucherId":10,"targetRedisStock":8,"rebuiltBuyerCount":2,
                                 "restoredRecoveryRows":1,"beforeEvidenceHash":"hash-before",
                                 "afterEvidenceHash":"hash-after","verified":true}
                                """));

        assertThat(client.transition(300L, "pay", null, false).getData())
                .isInstanceOfSatisfying(OrderView.class, order -> {
                    assertThat(order.id()).isEqualTo("300");
                    assertThat(order.status()).isEqualTo(2);
                    assertThat(order.inventoryRecovery()).isNull();
                });
        assertThat(client.repairConsistency(10L, "hash-before"))
                .isEqualTo(new ConsistencyRepairResult(10L, 8, 2, 1, "hash-before", "hash-after", true));
    }

    @Test
    void queriesTypedOrdersPreservingLargeIdsTimesAndAdditionalFields() {
        UserHolder.getUser().setRole("admin");
        String summary = """
                {"id":"9007199254740993","userId":7,"voucherId":10,"shopId":20,
                 "shopName":"店铺","voucherTitle":"双人券","status":4,"version":2,
                 "createTime":"2026-09-25T00:00:00.123+00:00","updateTime":"2026-09-25T00:00:00.123+00:00"}
                """.trim();
        String detail = summary.substring(0, summary.length() - 1) + """
                ,"payValue":9900,"actualValue":12000,"payTime":null,"useTime":null,"refundTime":null,
                "voucherSubTitle":null,"voucherRules":"预约后使用","inventoryRecovery":"RESTORE_PENDING",
                "additionalInfo":{"futureField":true}}
                """;
        server.expect(requestTo("http://commerce.test/commerce/v1/orders?page=1"))
                .andExpect(header("X-CityCopilot-User-Id", "7"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("[" + summary + "]"));
        server.expect(requestTo("http://commerce.test/commerce/v1/orders/9007199254740993?admin=true&merchant=false"))
                .andExpect(header("X-CityCopilot-Service-Token", "service-secret"))
                .andExpect(header("X-CityCopilot-User-Id", "7"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(detail));
        assertThat((java.util.List<?>) client.myOrders(null).getData()).singleElement()
                .isInstanceOfSatisfying(OrderSummary.class, order -> {
                    assertThat(order.id()).isEqualTo("9007199254740993");
                    assertThat(order.createTime()).hasTime(java.time.Instant.parse("2026-09-25T00:00:00.123Z").toEpochMilli());
                });
        assertThat(client.order(9007199254740993L, true).getData())
                .isInstanceOfSatisfying(OrderView.class, order -> {
                    assertThat(order.payValue()).isEqualTo(9900);
                    assertThat(order.actualValue()).isEqualTo(12000);
                    assertThat(order.payTime()).isNull();
                    assertThat(order.inventoryRecovery()).isEqualTo("RESTORE_PENDING");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "COMMERCE_ADMISSION_OUTCOME_UNKNOWN",
            "COMMERCE_DURABLE_ADMISSION_UNAVAILABLE"
    })
    void exposesRetryableAdmissionFailures(String code) {
        expectSeckill().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"success":false,"code":"%s","message":"Admission could not be confirmed","additionalInfo":{}}
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

    @Test
    void realSlowBodyKeepsOriginalKeyAndUnknownAdmissionOutcomeWithoutRetry() throws Exception {
        HttpServer downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool();
        downstream.setExecutor(workers);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        downstream.createContext("/commerce/v1/seckill/10", exchange -> {
            requests.incrementAndGet();
            receivedKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        downstream.start();
        var factories = new CrossServiceHttpConfig().commerceHttpFactories(Duration.ofSeconds(1),
                Duration.ofMillis(500), Duration.ofMillis(500), 1);
        var real = new CommercePortClient("http://127.0.0.1:" + downstream.getAddress().getPort(), "service-secret", factories);
        try (var ignored = RequestDeadline.open(Duration.ofSeconds(2), null)) {
            var error = catchThrowableOfType(() -> real.seckill(10L, "stable-admission-key"), CommercePortClient.CommerceHttpException.class);
            assertThat(error.code()).isEqualTo("COMMERCE_ADMISSION_OUTCOME_UNKNOWN");
            assertThat(error.status()).isEqualTo(503);
            assertThat(error.getMessage()).contains("same Idempotency-Key");
            assertThat(receivedKey.get()).isEqualTo("stable-admission-key");
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            release.countDown(); downstream.stop(0); workers.shutdownNow();
        }
    }

    @Test
    void merchantManagementViewIsSentAsMerchantScopeNotPlatformAdmin() {
        UserHolder.getUser().setRole("merchant");
        server.expect(requestTo("http://commerce.test/commerce/v1/orders/300/actions?admin=false&merchant=true"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":false,"code":"COMMERCE_SHOP_FORBIDDEN","message":"Actor does not own the shop"}
                                """));

        CommercePortClient.CommerceHttpException error = assertThrows(CommercePortClient.CommerceHttpException.class,
                () -> client.transition(300L, "use", null, true));

        assertThat(error.status()).isEqualTo(403);
        assertThat(error.code()).isEqualTo("COMMERCE_SHOP_FORBIDDEN");
    }
}
