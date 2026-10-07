package com.citycopilot.commerce;

import com.citycopilot.http.DeadlineHttpRequestFactory;
import com.citycopilot.http.RequestDeadline;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class PlaceAuthorityDeadlineTest {
    @Test @Timeout(30)
    void usesInheritedDeadlineAndFailsClosedWhenAuthorityBodyStalls() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        var release = new CountDownLatch(1);
        AtomicLong seen = new AtomicLong();
        server.createContext("/internal/v1/place/shops/7/authority", exchange -> {
            seen.set(Long.parseLong(exchange.getRequestHeaders().getFirst(DeadlineHttpRequestFactory.DEADLINE_HEADER)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var client = new PlaceAuthorityClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token",
                Duration.ofSeconds(1), Duration.ofSeconds(3), 1);
        long inherited = System.currentTimeMillis() + 700;
        try (var ignored = RequestDeadline.open(Duration.ofSeconds(5), Long.toString(inherited))) {
            long start = System.nanoTime();
            var error = catchThrowableOfType(() -> client.requireWriteAuthority(7, 9, false), CommerceApiException.class);
            assertThat(error.code()).isEqualTo("COMMERCE_PLACE_UNAVAILABLE");
            // 透传的是上游截止时间（约 700ms 后），而非本地 5s 预算；时钟换算允许几十毫秒误差。
            assertThat(seen.get()).isBetween(inherited - 50, inherited + 50);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown(); server.stop(0); workers.shutdownNow();
        }
    }
}
