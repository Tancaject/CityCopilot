package com.citycopilot.agent.gateway;

import com.citycopilot.agent.config.CoreServiceProperties;
import com.citycopilot.agent.remote.CoreServiceCallException;
import com.citycopilot.agent.remote.CoreServiceClient;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteToolGatewayTest {

    @Test
    void keepsCachedCatalogOnRefreshFailureButEventuallyMarksItStale() {
        CoreServiceClient client = mock(CoreServiceClient.class);
        when(client.tools(any()))
                .thenReturn(List.of(writeTool()))
                .thenThrow(new CoreServiceCallException(
                        "core_unavailable",
                        "Core unavailable",
                        true,
                        null
                ));
        CoreServiceProperties properties = properties();
        properties.setCatalogTtl(Duration.ofSeconds(10));
        properties.setCatalogMaxStaleness(Duration.ofSeconds(30));
        MutableClock clock = new MutableClock(Instant.parse("2026-08-04T00:00:00Z"));
        RemoteToolGateway gateway = new RemoteToolGateway(
                client,
                ToolExecutionGuard.direct(),
                properties,
                clock
        );

        assertEquals(1, gateway.tools().size());
        assertTrue(gateway.catalogStatus().fresh());
        assertEquals(RemoteToolGateway.RefreshOutcome.SUCCESS, gateway.catalogStatus().lastRefreshOutcome());

        clock.advance(Duration.ofSeconds(11));
        assertEquals(1, gateway.tools().size());
        assertTrue(gateway.catalogStatus().fresh());
        assertEquals(RemoteToolGateway.RefreshOutcome.FAILURE, gateway.catalogStatus().lastRefreshOutcome());
        assertEquals("core_unavailable", gateway.catalogStatus().lastFailureCode());

        clock.advance(Duration.ofSeconds(20));
        assertFalse(gateway.catalogStatus().fresh());
        assertEquals(1, gateway.catalogStatus().toolCount());
    }

    @Test
    void recordsRecoveryWithoutDiscardingTheMostRecentFailure() {
        CoreServiceClient client = mock(CoreServiceClient.class);
        when(client.tools(any()))
                .thenThrow(new CoreServiceCallException(
                        "core_unavailable",
                        "Core unavailable",
                        true,
                        null
                ))
                .thenReturn(List.of(writeTool()));
        CoreServiceProperties properties = properties();
        properties.setCatalogTtl(Duration.ZERO);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-04T00:00:00Z"));
        RemoteToolGateway gateway = new RemoteToolGateway(
                client,
                ToolExecutionGuard.direct(),
                properties,
                clock
        );

        gateway.tools();
        clock.advance(Duration.ofSeconds(2));
        gateway.tools();

        RemoteToolGateway.CatalogStatus status = gateway.catalogStatus();
        assertEquals(RemoteToolGateway.RefreshOutcome.SUCCESS, status.lastRefreshOutcome());
        assertTrue(status.fresh());
        assertEquals(Instant.parse("2026-08-04T00:00:00Z"), status.lastFailedRefreshAt());
        assertEquals("core_unavailable", status.lastFailureCode());
    }

    @Test
    void returnsControlledErrorWhenCoreIsUnavailable() {
        CoreServiceClient client = mock(CoreServiceClient.class);
        when(client.tools(any())).thenReturn(List.of(writeTool()));
        when(client.invoke(anyString(), any(), any())).thenThrow(
                new CoreServiceCallException("core_unavailable", "Core unavailable", true, null)
        );
        RemoteToolGateway gateway = new RemoteToolGateway(client, ToolExecutionGuard.direct(), properties());

        ToolResult result = gateway.invoke(
                "write_demo",
                Map.of("value", "x"),
                new ToolCallContext("42", Set.of("order:write"), "Bearer token", "request-1", "stable-key")
        );

        assertFalse(result.ok());
        assertEquals("core_unavailable", result.error().code());
        assertTrue(result.error().retryable());
    }

    @Test
    void onlyMarksWritesRetrySafeWhenStableKeyExists() {
        CoreServiceClient client = mock(CoreServiceClient.class);
        when(client.tools(any())).thenReturn(List.of(writeTool()));
        when(client.invoke(anyString(), any(), any())).thenReturn(ToolInvocationResponse.success("ok"));
        AtomicBoolean observedRetrySafe = new AtomicBoolean();
        ToolExecutionGuard guard = (tool, invocation) -> {
            observedRetrySafe.set(tool.idempotent());
            return invocation.call();
        };
        RemoteToolGateway gateway = new RemoteToolGateway(client, guard, properties());

        gateway.invoke("write_demo", Map.of(), new ToolCallContext("42", Set.of()));
        assertFalse(observedRetrySafe.get());

        gateway.invoke(
                "write_demo",
                Map.of(),
                new ToolCallContext("42", Set.of(), "Bearer token", "request-1", "stable-key")
        );
        assertTrue(observedRetrySafe.get());
    }

    private ToolDefinition writeTool() {
        return new ToolDefinition("write_demo", "write", List.of("order:write"), 60, false, List.of());
    }

    private CoreServiceProperties properties() {
        CoreServiceProperties properties = new CoreServiceProperties();
        properties.setCatalogTtl(Duration.ofMinutes(1));
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
