package com.citycopilot.agent.gateway;

import com.citycopilot.agent.config.CoreServiceProperties;
import com.citycopilot.agent.remote.CoreServiceCallException;
import com.citycopilot.agent.remote.CoreServiceClient;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public final class RemoteToolGateway implements ToolGatewayClient {
    private final CoreServiceClient coreServiceClient;
    private final ToolExecutionGuard executionGuard;
    private final Duration catalogTtl;
    private final Duration catalogMaxStaleness;
    private final Clock clock;
    private volatile CatalogState state = CatalogState.empty();

    @Autowired
    public RemoteToolGateway(
            CoreServiceClient coreServiceClient,
            ToolExecutionGuard executionGuard,
            CoreServiceProperties properties
    ) {
        this(coreServiceClient, executionGuard, properties, Clock.systemUTC());
    }

    RemoteToolGateway(
            CoreServiceClient coreServiceClient,
            ToolExecutionGuard executionGuard,
            CoreServiceProperties properties,
            Clock clock
    ) {
        this.coreServiceClient = coreServiceClient;
        this.executionGuard = executionGuard;
        this.catalogTtl = nonNegative(properties.getCatalogTtl(), Duration.ofSeconds(30));
        Duration configuredMaxStaleness = nonNegative(
                properties.getCatalogMaxStaleness(),
                Duration.ofMinutes(2)
        );
        this.catalogMaxStaleness = configuredMaxStaleness.compareTo(catalogTtl) < 0
                ? catalogTtl
                : configuredMaxStaleness;
        this.clock = clock;
    }

    @Override
    public List<ToolMetadata> tools() {
        CatalogState current = state;
        if (clock.instant().isBefore(current.nextRefreshAt())) {
            return current.catalog();
        }
        return refreshCatalog(false);
    }

    public CatalogStatus catalogStatus() {
        CatalogState current = state;
        Instant now = clock.instant();
        boolean fresh = !current.catalog().isEmpty()
                && current.lastSuccessfulRefreshAt() != null
                && now.isBefore(current.freshUntil());
        return new CatalogStatus(
                current.catalog().size(),
                fresh,
                current.lastRefreshOutcome(),
                current.lastRefreshAttemptAt(),
                current.lastSuccessfulRefreshAt(),
                current.lastFailedRefreshAt(),
                current.lastFailureCode(),
                current.freshUntil(),
                current.nextRefreshAt()
        );
    }

    @Override
    public ToolResult invoke(String name, Map<String, Object> args, ToolCallContext context) {
        ToolMetadata metadata = findTool(name);
        if (metadata == null) {
            return ToolResult.failure("unknown_tool", "Unknown agent tool: " + name, false);
        }
        boolean keyedWrite = context != null
                && context.idempotencyKey() != null
                && !context.idempotencyKey().isBlank();
        ToolMetadata guardedMetadata = keyedWrite ? metadata.asRetrySafe() : metadata;
        try {
            ToolInvocationResponse response = (ToolInvocationResponse) executionGuard.execute(
                    guardedMetadata,
                    () -> invokeCore(name, args, context)
            );
            if (response.ok()) {
                return ToolResult.success(response.data());
            }
            return ToolResult.failure(
                    response.error() == null ? "core_tool_error" : response.error().code(),
                    response.error() == null ? "Core tool call failed" : response.error().message(),
                    response.error() != null && response.error().retryable()
            );
        } catch (ToolExecutionException error) {
            return ToolResult.failure(error.code(), error.getMessage(), error.retryable());
        } catch (Exception error) {
            return ToolResult.failure(
                    "core_tool_error",
                    error.getMessage() == null ? "Core tool call failed" : error.getMessage(),
                    false
            );
        }
    }

    private ToolInvocationResponse invokeCore(String name, Map<String, Object> args, ToolCallContext context) {
        try {
            return coreServiceClient.invoke(name, args, context);
        } catch (CoreServiceCallException error) {
            throw new ToolExecutionException(error.code(), error.getMessage(), error.retryable());
        }
    }

    private ToolMetadata findTool(String name) {
        ToolMetadata existing = state.catalog().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        return refreshCatalog(true).stream().filter(tool -> tool.name().equals(name)).findFirst().orElse(null);
    }

    private synchronized List<ToolMetadata> refreshCatalog(boolean force) {
        CatalogState current = state;
        Instant now = clock.instant();
        if (!force && now.isBefore(current.nextRefreshAt())) {
            return current.catalog();
        }
        try {
            List<ToolMetadata> refreshed = coreServiceClient.tools(null).stream()
                    .map(ToolMetadata::fromDefinition)
                    .toList();
            List<ToolMetadata> immutableCatalog = List.copyOf(refreshed);
            state = new CatalogState(
                    immutableCatalog,
                    now.plus(catalogTtl),
                    now.plus(catalogMaxStaleness),
                    RefreshOutcome.SUCCESS,
                    now,
                    now,
                    current.lastFailedRefreshAt(),
                    current.lastFailureCode()
            );
        } catch (CoreServiceCallException error) {
            state = new CatalogState(
                    current.catalog(),
                    now.plusSeconds(2),
                    current.freshUntil(),
                    RefreshOutcome.FAILURE,
                    now,
                    current.lastSuccessfulRefreshAt(),
                    now,
                    error.code()
            );
        }
        return state.catalog();
    }

    private static Duration nonNegative(Duration configured, Duration fallback) {
        Duration value = configured == null ? fallback : configured;
        return value.isNegative() ? Duration.ZERO : value;
    }

    public enum RefreshOutcome {
        NEVER,
        SUCCESS,
        FAILURE
    }

    public record CatalogStatus(
            int toolCount,
            boolean fresh,
            RefreshOutcome lastRefreshOutcome,
            Instant lastRefreshAttemptAt,
            Instant lastSuccessfulRefreshAt,
            Instant lastFailedRefreshAt,
            String lastFailureCode,
            Instant freshUntil,
            Instant nextRefreshAt
    ) {
    }

    private record CatalogState(
            List<ToolMetadata> catalog,
            Instant nextRefreshAt,
            Instant freshUntil,
            RefreshOutcome lastRefreshOutcome,
            Instant lastRefreshAttemptAt,
            Instant lastSuccessfulRefreshAt,
            Instant lastFailedRefreshAt,
            String lastFailureCode
    ) {
        private static CatalogState empty() {
            return new CatalogState(
                    List.of(),
                    Instant.EPOCH,
                    Instant.EPOCH,
                    RefreshOutcome.NEVER,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}
