package com.citycopilot.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class DeadlineHttpRequestFactoryTest {
    private HttpServer server;
    private ExecutorService workers;
    private final CountDownLatch releaseServer = new CountDownLatch(1);
    private String base;

    @BeforeEach void start() throws IOException {
        workers = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() {
        releaseServer.countDown();
        server.stop(0);
        workers.shutdownNow();
    }

    @Test void timesOutBeforeHeadersAndReleasesPermit() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        server.createContext("/headers", exchange -> {
            accepted.countDown(); awaitRelease(); exchange.close();
        });
        okEndpoint();
        Semaphore capacity = new Semaphore(1);
        RestClient client = client(Duration.ofMillis(500), capacity);
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/headers").retrieve().body(String.class)).isInstanceOf(RestClientException.class);
        assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(capacity.availablePermits()).isEqualTo(1);
        assertThat(client.get().uri("/ok").retrieve().body(String.class)).isEqualTo("ok");
    }

    @Test void timesOutBodyAfterHeadersAndReleasesPermit() throws Exception {
        CountDownLatch headersSent = stalledBody();
        okEndpoint();
        Semaphore capacity = new Semaphore(1);
        RestClient client = client(Duration.ofMillis(500), capacity);
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/body").retrieve().body(String.class)).isInstanceOf(RestClientException.class);
        assertThat(headersSent.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(capacity.availablePermits()).isEqualTo(1);
        assertThat(client.get().uri("/ok").retrieve().body(String.class)).isEqualTo("ok");
    }

    @Test void cancellationClosesAnAlreadyExposedBody() throws Exception {
        CountDownLatch headersSent = stalledBody();
        Semaphore capacity = new Semaphore(1);
        RequestDeadline budget = RequestDeadline.create(Duration.ofSeconds(5), null);
        RestClient client = RestClient.builder().baseUrl(base).requestFactory(new DeadlineHttpRequestFactory(
                Duration.ofSeconds(1), Duration.ofSeconds(5), () -> budget, true, capacity)).build();
        try (RequestDeadline.Scope ignored = budget.bind()) {
            var pending = workers.submit(() -> catchThrowable(() -> client.get().uri("/body").retrieve().body(String.class)));
            assertThat(headersSent.await(2, TimeUnit.SECONDS)).isTrue();
            ignored.close();
            assertThat(pending.get(2, TimeUnit.SECONDS)).isInstanceOf(RestClientException.class);
            assertThat(capacity.availablePermits()).isEqualTo(1);
        }
    }

    @Test void sharedTargetCapacityRejectsWithoutQueueAndRecoversOnClose() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/ok", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes()); exchange.close();
        });
        Semaphore capacity = new Semaphore(1);
        var first = new DeadlineHttpRequestFactory(Duration.ofSeconds(1), Duration.ofSeconds(3), () -> null, false, capacity);
        var second = new DeadlineHttpRequestFactory(Duration.ofSeconds(1), Duration.ofSeconds(3), () -> null, false, capacity);
        try (ClientHttpResponse held = first.createRequest(URI.create(base + "/ok"), HttpMethod.GET).execute()) {
            assertThatThrownBy(() -> second.createRequest(URI.create(base + "/ok"), HttpMethod.GET).execute())
                    .isInstanceOf(IOException.class).hasMessageContaining("capacity exhausted");
            assertThat(requests.get()).isEqualTo(1);
            assertThat(held.getBody().readAllBytes()).isEqualTo("ok".getBytes());
        }
        try (ClientHttpResponse next = second.createRequest(URI.create(base + "/ok"), HttpMethod.GET).execute()) {
            assertThat(next.getBody().readAllBytes()).isEqualTo("ok".getBytes());
        }
        assertThat(requests.get()).isEqualTo(2);
        assertThat(capacity.availablePermits()).isEqualTo(1);
    }

    @Test void sequentialHopsConsumeOneDeadlineInsteadOfReceivingFreshTimeouts() throws Exception {
        AtomicLong firstHeader = new AtomicLong();
        AtomicLong secondHeader = new AtomicLong();
        server.createContext("/first", exchange -> {
            firstHeader.set(Long.parseLong(exchange.getRequestHeaders().getFirst(DeadlineHttpRequestFactory.DEADLINE_HEADER)));
            try { Thread.sleep(180); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("ok".getBytes()); exchange.close();
        });
        server.createContext("/second", exchange -> {
            secondHeader.set(Long.parseLong(exchange.getRequestHeaders().getFirst(DeadlineHttpRequestFactory.DEADLINE_HEADER)));
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('a'); exchange.getResponseBody().flush();
            awaitRelease(); exchange.close();
        });
        RestClient client = client(Duration.ofSeconds(3), new Semaphore(1));
        try (var ignored = RequestDeadline.open(Duration.ofMillis(600), null)) {
            long start = System.nanoTime();
            assertThat(client.get().uri("/first").retrieve().body(String.class)).isEqualTo("ok");
            assertThatThrownBy(() -> client.get().uri("/second").retrieve().body(String.class)).isInstanceOf(RestClientException.class);
            assertThat(secondHeader.get()).isBetween(firstHeader.get() - 5, firstHeader.get() + 5);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1600));
        }
        assertThat(RequestDeadline.current()).isNull();
    }

    private RestClient client(Duration timeout, Semaphore capacity) {
        return RestClient.builder().baseUrl(base).requestFactory(new DeadlineHttpRequestFactory(
                Duration.ofSeconds(1), timeout, RequestDeadline::current, true, capacity)).build();
    }
    private CountDownLatch stalledBody() {
        CountDownLatch sent = new CountDownLatch(1);
        server.createContext("/body", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('a'); exchange.getResponseBody().flush(); sent.countDown();
            awaitRelease(); exchange.close();
        });
        return sent;
    }
    private void okEndpoint() {
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("ok".getBytes()); exchange.close();
        });
    }
    private void awaitRelease() {
        try { releaseServer.await(6, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
