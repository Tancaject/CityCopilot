package com.citycopilot.mcp;

import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.http.DeadlineHttpRequestFactory;
import com.citycopilot.http.RequestDeadline;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.ServerRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpDeadlineTest {
    @Test
    void catalogAndWriteShareDeadlineAndStalledBodyDoesNotTriggerASecondWrite() throws Exception {
        CountDownLatch finishResponse = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<String> sentKey = new AtomicReference<>();
        AtomicReference<Integer> sentVersion = new AtomicReference<>();
        AtomicReference<String> catalogDeadline = new AtomicReference<>();
        AtomicReference<String> writeDeadline = new AtomicReference<>();
        JsonMapper json = JsonMapper.builder().build();
        byte[] catalog = json.writeValueAsBytes(List.of(tool(2)));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverThreads = Executors.newFixedThreadPool(2);
        server.setExecutor(serverThreads);
        server.createContext("/internal/v1/tools", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (exchange.getRequestMethod().equals("GET")) {
                    catalogDeadline.set(exchange.getRequestHeaders().getFirst(DeadlineHttpRequestFactory.DEADLINE_HEADER));
                    exchange.sendResponseHeaders(200, catalog.length);
                    exchange.getResponseBody().write(catalog);
                } else {
                    sentVersion.set(json.readTree(exchange.getRequestBody().readAllBytes()).get("expectedVersion").asInt());
                    writes.incrementAndGet();
                    sentKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    writeDeadline.set(exchange.getRequestHeaders().getFirst(DeadlineHttpRequestFactory.DEADLINE_HEADER));
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    try { finishResponse.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            }
        });
        server.start();
        McpCoreProperties properties = new McpCoreProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRequestTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setMaxConcurrent(1);
        McpToolService service = new McpToolService(new McpCoreClient(properties), properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", "keep-this-key");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        long started = System.nanoTime();
        try {
            ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                    service.call(new McpToolCallRequest("write_probe", Map.of(), null, Set.of(), 2)));
            assertEquals(504, error.getStatusCode().value());
            assertTrue(error.getMessage().contains("outcome is unknown"));
            assertTrue(error.getMessage().contains("original idempotency key"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
            assertEquals(1, writes.get());
            assertEquals("keep-this-key", sentKey.get());
            assertEquals(2, sentVersion.get());
            assertNotNull(catalogDeadline.get());
            assertNotNull(writeDeadline.get());
            assertTrue(Long.parseLong(writeDeadline.get()) <= Long.parseLong(catalogDeadline.get()) + 5);
            assertNull(RequestDeadline.current());
        } finally {
            RequestContextHolder.resetRequestAttributes();
            finishResponse.countDown();
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }

    @Test
    void rejectsExcessLogicalOperationBeforeCallingCoreAndReleasesCapacity() throws Exception {
        McpCoreClient client = mock(McpCoreClient.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(client.tools(any())).thenReturn(List.of(tool()));
        McpCoreProperties properties = new McpCoreProperties();
        properties.setMaxConcurrent(1);
        McpToolService service = new McpToolService(client, properties);
        ToolCallback callback = new CityCopilotMcpToolCallbackProvider()
                .cityCopilotToolCallbackProvider(service).getToolCallbacks()[0];
        clearInvocations(client);
        when(client.tools(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(finish.await(3, TimeUnit.SECONDS));
            return List.of(tool());
        });
        var worker = Executors.newSingleThreadExecutor();
        try {
            var first = worker.submit(service::catalog);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                    service.call(new McpToolCallRequest("write_probe", Map.of(), null, Set.of())));
            assertEquals(503, error.getStatusCode().value());
            String protocolError = callback.call("{}");
            assertTrue(protocolError.contains("mcp_overloaded"));
            assertTrue(protocolError.contains("\"retryable\":false"));
            verify(client, never()).invoke(anyString(), any(), any(), anyInt());
            verify(client).tools(any());
            finish.countDown();
            assertEquals(1, first.get(2, TimeUnit.SECONDS).size());
            assertEquals(1, service.catalog().size());
            assertNull(RequestDeadline.current());
        } finally {
            finish.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void sdkCallbackCarriesPostIdentityAndDeadlineAcrossThreadsWithoutLeakingThem() throws Exception {
        McpCoreProperties properties = new McpCoreProperties();
        properties.setRequestTimeout(Duration.ofSeconds(8));
        McpCoreClient client = mock(McpCoreClient.class);
        when(client.tools(any())).thenReturn(List.of(tool()));
        ToolCallback callback = callback(client, properties);
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/messages");
        request.addHeader("Authorization", "Bearer test-only");
        request.addHeader("X-Request-Id", "captured-request");
        request.addHeader("Idempotency-Key", "captured-key");
        long inherited = System.currentTimeMillis() + 2_000;
        request.addHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER, Long.toString(inherited));
        McpTransportContext captured = McpTransportConfiguration.capture(ServerRequest.create(request, List.of()), properties);
        assertTrue(((RequestDeadline) captured.get(McpTransportConfiguration.DEADLINE)).deadlineEpochMillis() <= inherited);
        assertNull(RequestDeadline.current());

        when(client.invoke(anyString(), any(), any(), anyInt())).thenAnswer(invocation -> {
            McpRequestMetadata metadata = invocation.getArgument(2);
            assertEquals("Bearer test-only", metadata.authorization());
            assertEquals("captured-request", metadata.requestId());
            assertEquals("captured-key", metadata.idempotencyKeyFor("write_probe"));
            assertTrue(RequestDeadline.current().deadlineEpochMillis() <= inherited);
            return ToolInvocationResponse.success("forwarded");
        });
        when(exchange.transportContext()).thenReturn(captured);
        var specification = McpToolUtils.toSyncToolSpecification(callback);
        var worker = Executors.newSingleThreadExecutor();
        try {
            String result = worker.submit(() -> {
                var response = specification.callHandler().apply(exchange, new McpSchema.CallToolRequest("write_probe", Map.of()));
                assertNull(RequestDeadline.current());
                assertNull(McpRequestMetadata.current().authorization());
                assertFalse(Boolean.TRUE.equals(response.isError()));
                return ((McpSchema.TextContent) response.content().get(0)).text();
            }).get(3, TimeUnit.SECONDS);
            assertTrue(result.contains("forwarded"));
            verify(client).invoke(eq("write_probe"), any(), any(), eq(1));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void messageCannotExtendLocalDeadlineAndExpiredCallbackNeverInvokesCore() {
        McpCoreProperties properties = new McpCoreProperties();
        properties.setRequestTimeout(Duration.ofSeconds(1));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/messages");
        request.addHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER, Long.toString(Long.MAX_VALUE));
        long started = System.currentTimeMillis();
        McpTransportContext future = McpTransportConfiguration.capture(ServerRequest.create(request, List.of()), properties);
        assertTrue(((RequestDeadline) future.get(McpTransportConfiguration.DEADLINE)).deadlineEpochMillis() <= started + 1_100);

        request = new MockHttpServletRequest("POST", "/mcp/messages");
        request.addHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER, Long.toString(System.currentTimeMillis() - 1));
        McpTransportContext expired = McpTransportConfiguration.capture(ServerRequest.create(request, List.of()), properties);
        McpCoreClient client = mock(McpCoreClient.class);
        when(client.tools(any())).thenReturn(List.of(tool()));
        ToolCallback callback = callback(client, properties);
        clearInvocations(client);
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(expired);
        String result = callback.call("{}", new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange)));
        assertTrue(result.contains("mcp_deadline_exceeded"));
        assertTrue(result.contains("\"retryable\":false"));
        assertTrue(result.contains("outcome is unknown"));
        assertTrue(result.contains("original idempotency key"));
        verifyNoInteractions(client);
        assertNull(RequestDeadline.current());

        MockHttpServletRequest sse = new MockHttpServletRequest("GET", "/mcp/sse");
        assertSame(McpTransportContext.EMPTY, McpTransportConfiguration.capture(ServerRequest.create(sse, List.of()), properties));
    }

    private static ToolCallback callback(McpCoreClient client, McpCoreProperties properties) {
        return new CityCopilotMcpToolCallbackProvider().cityCopilotToolCallbackProvider(
                new McpToolService(client, properties)).getToolCallbacks()[0];
    }

    private static ToolDefinition tool() {
        return tool(1);
    }

    private static ToolDefinition tool(int version) {
        return new ToolDefinition("write_probe", "Write probe", List.of(), 60, false, List.of(),
                null, null, null, null, "core", version, Map.of());
    }
}
