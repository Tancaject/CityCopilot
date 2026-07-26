package com.citycopilot.agent.remote;

import com.citycopilot.agent.config.CoreServiceProperties;
import com.citycopilot.contract.security.ServiceUser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreServiceClientTest {
    private HttpServer server;
    private final AtomicReference<String> serviceToken = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestId = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/session", exchange -> {
            serviceToken.set(exchange.getRequestHeaders().getFirst(CoreServiceClient.SERVICE_TOKEN_HEADER));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestId.set(exchange.getRequestHeaders().getFirst(CoreServiceClient.REQUEST_ID_HEADER));
            byte[] response = "{\"id\":42,\"nickName\":\"demo\",\"role\":\"user\",\"permissions\":[\"order:read\"],\"authenticated\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void forwardsServiceAndUserIdentityHeaders() {
        CoreServiceProperties properties = new CoreServiceProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setServiceToken("service-secret");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
        CoreServiceClient client = new CoreServiceClient(properties);

        ServiceUser user = client.resolveSession("Bearer user-token", "request-123");

        assertTrue(user.authenticated());
        assertEquals(42L, user.id());
        assertEquals("service-secret", serviceToken.get());
        assertEquals("Bearer user-token", authorization.get());
        assertEquals("request-123", requestId.get());
    }
}
