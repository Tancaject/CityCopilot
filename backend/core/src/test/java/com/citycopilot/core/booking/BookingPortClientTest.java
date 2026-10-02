package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingContracts.ActionRequest;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import com.citycopilot.core.config.CrossServiceHttpConfig;
import com.citycopilot.http.RequestDeadline;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class BookingPortClientTest {
    @Test
    void realSlowBodyRetainsUnknownWriteAndOriginalKey() throws Exception {
        var downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool();
        downstream.setExecutor(workers);
        var release = new CountDownLatch(1);
        var key = new AtomicReference<String>();
        var count = new AtomicInteger();
        downstream.createContext("/commerce/v1/admin/bookings/booking-1/check-in", exchange -> {
            count.incrementAndGet(); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        downstream.start();
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        var factories = new CrossServiceHttpConfig().commerceHttpFactories(Duration.ofSeconds(1),
                Duration.ofMillis(500), Duration.ofMillis(500), 1);
        var client = new BookingPortClient("http://127.0.0.1:" + downstream.getAddress().getPort(), "test-service-token", factories);
        try (var ignored = RequestDeadline.open(Duration.ofSeconds(2), null)) {
            var error = catchThrowableOfType(() -> client.checkIn("booking-1", new ActionRequest(1L, null, "proof"), "original-key"),
                    BookingPortClient.Unavailable.class);
            assertThat(error.outcomeUnknown).isTrue();
            assertThat(key.get()).isEqualTo("original-key");
            assertThat(count.get()).isEqualTo(1);
            assertThat(new BookingExceptionAdvice().unavailable(error).getStatusCode().value()).isEqualTo(503);
        } finally {
            UserHolder.removeUser(); release.countDown(); downstream.stop(0); workers.shutdownNow();
        }
    }

    @Test
    void merchantIdentityAndConflictSurviveTheFacade() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://commerce.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BookingPortClient client = new BookingPortClient(builder.build(), "test-service-token");
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setRole("merchant");
        UserHolder.saveUser(user);
        String body = "{\"code\":\"BOOKING_VERSION_CONFLICT\",\"httpStatus\":409,\"message\":\"请刷新预约\",\"booking\":null}";
        server.expect(requestTo("http://commerce.test/commerce/v1/admin/bookings/booking-1/check-in"))
                .andExpect(header("X-CityCopilot-User-Id", "7"))
                .andExpect(header("X-CityCopilot-Platform-Admin", "false"))
                .andExpect(header("Idempotency-Key", "check-in-1"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(body));
        try {
            var error = catchThrowableOfType(() -> client.checkIn("booking-1", new ActionRequest(1L, null, "proof"), "check-in-1"),
                    RestClientResponseException.class);
            var response = new BookingExceptionAdvice().downstream(error);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody()).isEqualTo(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            server.verify();
        } finally {
            UserHolder.removeUser();
        }
    }
}
