package com.citycopilot.agent.budget;

import com.sun.net.httpserver.HttpServer;
import com.citycopilot.http.DeadlineHttpRequestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BudgetHttpRequestFactoryTest {
    @Test void timeoutCoversBodyAfterHeadersAndClosesTheExchange() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('a'); exchange.getResponseBody().flush();
                release.await(3, TimeUnit.SECONDS);
            } catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        try {
            RestTemplate client = new RestTemplate(new DeadlineHttpRequestFactory(Duration.ofSeconds(1), Duration.ofMillis(150), AgentBudget::current, false, 1));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(Exception.class,
                    () -> client.getForObject("http://127.0.0.1:" + server.getAddress().getPort() + "/slow", String.class)));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void explicitCancellationStopsStreamAndPropagatesInternalDeadline() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> deadline = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            try {
                deadline.set(exchange.getRequestHeaders().getFirst(DeadlineHttpRequestFactory.DEADLINE_HEADER));
                exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('a'); exchange.getResponseBody().flush();
                started.countDown(); release.await(3, TimeUnit.SECONDS);
            } catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        AgentBudgetProperties limits = new AgentBudgetProperties(); limits.setConcurrency(1);
        try (AgentExecutionRuntime runtime = new AgentExecutionRuntime(limits)) {
            AgentBudget budget = runtime.newBudget();
            RestTemplate client = new RestTemplate(new DeadlineHttpRequestFactory(Duration.ofSeconds(1), Duration.ofSeconds(5), AgentBudget::current, true, 1));
            var task = runtime.submit(budget, () -> client.getForObject(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/stream", String.class));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            budget.cancel("agent_cancelled");
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(Exception.class, task::get));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertTrue(runtime.call(() -> true)));
            assertNotNull(deadline.get());
            assertTrue(Long.parseLong(deadline.get()) > System.currentTimeMillis());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void rawResponseIsBoundedBeforeJsonParsing() throws Exception {
        AgentBudgetProperties limits = new AgentBudgetProperties(); limits.setModelResponseBytes(8);
        RestTemplate client = new RestTemplate();
        client.getInterceptors().add(new BudgetHttpInterceptor(true));
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(client).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("0123456789", org.springframework.http.MediaType.TEXT_PLAIN));
        try (AgentBudget.Scope ignored = new AgentBudget(limits).bind()) {
            assertEquals("agent_model_response_too_large", assertThrows(AgentBudgetException.class,
                    () -> client.getForObject("http://localhost/test", String.class)).code());
        }
        server.verify();
    }
}
