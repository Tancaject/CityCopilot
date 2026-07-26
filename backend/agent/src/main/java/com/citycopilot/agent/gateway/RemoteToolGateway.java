package com.citycopilot.agent.gateway;

import com.citycopilot.agent.config.CoreServiceProperties;
import com.citycopilot.agent.remote.CoreServiceCallException;
import com.citycopilot.agent.remote.CoreServiceClient;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public final class RemoteToolGateway implements ToolGatewayClient {
    private final CoreServiceClient coreServiceClient;
    private final ToolExecutionGuard executionGuard;
    private final Duration catalogTtl;
    private volatile List<ToolMetadata> catalog = List.of();
    private volatile Instant catalogExpiresAt = Instant.EPOCH;

    public RemoteToolGateway(
            CoreServiceClient coreServiceClient,
            ToolExecutionGuard executionGuard,
            CoreServiceProperties properties
    ) {
        this.coreServiceClient = coreServiceClient;
        this.executionGuard = executionGuard;
        this.catalogTtl = properties.getCatalogTtl() == null
                ? Duration.ofSeconds(30)
                : properties.getCatalogTtl();
    }

    @Override
    public List<ToolMetadata> tools() {
        if (Instant.now().isBefore(catalogExpiresAt)) {
            return catalog;
        }
        return refreshCatalog(false);
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
        ToolMetadata existing = catalog.stream().filter(tool -> tool.name().equals(name)).findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        return refreshCatalog(true).stream().filter(tool -> tool.name().equals(name)).findFirst().orElse(null);
    }

    private synchronized List<ToolMetadata> refreshCatalog(boolean force) {
        if (!force && Instant.now().isBefore(catalogExpiresAt)) {
            return catalog;
        }
        try {
            List<ToolMetadata> refreshed = coreServiceClient.tools(null).stream()
                    .map(ToolMetadata::fromDefinition)
                    .toList();
            catalog = List.copyOf(refreshed);
            catalogExpiresAt = Instant.now().plus(catalogTtl.isNegative() ? Duration.ZERO : catalogTtl);
        } catch (CoreServiceCallException ignored) {
            catalogExpiresAt = Instant.now().plusSeconds(2);
        }
        return catalog;
    }
}
