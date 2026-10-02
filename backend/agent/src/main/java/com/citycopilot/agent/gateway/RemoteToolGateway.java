package com.citycopilot.agent.gateway;

import com.citycopilot.agent.config.CoreServiceProperties;
import com.citycopilot.agent.budget.AgentBudget;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.contract.commerce.CommerceAdmissionOutcomePolicy;
import com.citycopilot.agent.remote.CoreServiceCallException;
import com.citycopilot.agent.remote.CoreServiceClient;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolContractSnapshot;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 到 Core 的远程工具网关。
 *
 * <p>从 Core 拉取权威工具目录并带期限缓存，调用时应用可靠性保护，再把跨服务响应与异常统一转换为 {@link ToolResult}。</p>
 */
@Component
public final class RemoteToolGateway implements ToolGatewayClient {
    private static final Set<String> UNRESOLVED_WRITE_CODES = Set.of(
            "empty_core_response", "operation_in_progress", "idempotency_store_error",
            CommerceAdmissionOutcomePolicy.COMMERCE_ADMISSION_OUTCOME_UNKNOWN
    );
    /** 访问 Core 内部工具目录和调用接口的 HTTP 客户端。 */
    private final CoreServiceClient coreServiceClient;
    /** 限流、熔断、超时和安全重试保护层。 */
    private final ToolExecutionGuard executionGuard;
    /** 成功目录在正常情况下的刷新周期。 */
    private final Duration catalogTtl;
    /** 最近成功目录可被视为新鲜的最长期限。 */
    private final Duration catalogMaxStaleness;
    /** 可注入时钟，保证缓存边界可测试。 */
    private final Clock clock;
    /** 原子替换的目录及刷新状态快照。 */
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

    /**
     * 返回缓存工具目录；达到刷新时间时同步向 Core 刷新。
     *
     * @return 最新成功目录，刷新失败时保留旧快照并缩短重试间隔
     */
    @Override
    public List<ToolMetadata> tools() {
        AgentBudget.checkCurrent();
        CatalogState current = state;
        if (clock.instant().isBefore(current.nextRefreshAt())) {
            return current.catalog();
        }
        return refreshCatalog(false);
    }

    @Override
    public synchronized List<ToolMetadata> refreshTools() {
        List<ToolMetadata> refreshed = refreshCatalog(true);
        if (state.lastRefreshOutcome() != RefreshOutcome.SUCCESS) {
            throw new ToolExecutionException("tool_catalog_unavailable",
                    "无法从 Core 取得当前工具契约，请稍后重试确认；待确认操作尚未消费。", false);
        }
        return refreshed;
    }

    /** @return 供健康探针读取的不可变目录刷新状态 */
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

    /**
     * 在 Core 权威目录中查找并执行工具。
     *
     * @param name 工具注册名
     * @param args 工具参数
     * @param context 用户授权、请求号和幂等上下文
     * @return 标准工具结果；未知工具或跨服务异常不会直接抛给执行器
     */
    @Override
    public ToolResult invoke(String name, Map<String, Object> args, ToolCallContext context) {
        AgentBudget budget = AgentBudget.current();
        if (budget != null) { budget.tool(); budget.context(args); }
        ToolMetadata metadata = findTool(name);
        if (metadata == null) {
            return ToolResult.failure("unknown_tool", "Unknown agent tool: " + name, false);
        }
        if (context != null && context.expectedVersion() != null
                && context.expectedVersion() != metadata.contractVersion()) {
            return ToolResult.failure(ToolContractSnapshot.MISMATCH,
                    "工具契约已变化：" + name + "。请重新审阅计划；本次未执行。", false);
        }
        ToolCallContext versionedContext = context == null
                ? new ToolCallContext(null, Set.of()).withExpectedVersion(metadata.contractVersion())
                : context.expectedVersion() == null ? context.withExpectedVersion(metadata.contractVersion()) : context;
        boolean keyedWrite = context != null
                && context.idempotencyKey() != null
                && !context.idempotencyKey().isBlank();
        // 写调用只有携带幂等键时才能视为可安全重试，保护器据此决定是否重放。
        ToolMetadata guardedMetadata = keyedWrite ? metadata.asRetrySafe() : metadata;
        try {
            ToolInvocationResponse response = (ToolInvocationResponse) executionGuard.execute(
                    guardedMetadata,
                    () -> {
                        boolean write = budget != null && metadata.sideEffect() != ToolSideEffect.NONE;
                        if (write) budget.writeStarted();
                        ToolInvocationResponse attemptResponse = invokeCore(name, args, versionedContext);
                        if (write && attemptResponse.error() != null
                                && UNRESOLVED_WRITE_CODES.contains(attemptResponse.error().code())) {
                            budget.reject("agent_write_outcome_unknown");
                        }
                        if (write) budget.writeCompleted();
                        return attemptResponse;
                    }
            );
            if (budget != null) { budget.check(); budget.toolResult(response); }
            if (response.ok()) {
                return ToolResult.success(response.data());
            }
            return ToolResult.failure(
                    response.error() == null ? "core_tool_error" : response.error().code(),
                    response.error() == null ? "Core tool call failed" : response.error().message(),
                    response.error() != null && response.error().retryable()
            );
        } catch (ToolExecutionException error) {
            AgentBudget.rethrowIfStopped(error);
            if (budget != null && budget.writeOutcomeUnknown()) budget.reject("agent_write_outcome_unknown");
            return ToolResult.failure(error.code(), error.getMessage(), error.retryable());
        } catch (Exception error) {
            AgentBudget.rethrowIfStopped(error);
            if (budget != null && budget.writeOutcomeUnknown()) budget.reject("agent_write_outcome_unknown");
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

    /** 先查缓存；未命中时强制刷新一次，兼容 Core 刚发布的新工具。 */
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

    /**
     * 串行刷新工具目录，失败时保留最后成功快照并在短间隔后重试。
     */
    private synchronized List<ToolMetadata> refreshCatalog(boolean force) {
        AgentBudget.checkCurrent();
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

    /** 最近一次目录刷新结果。 */
    public enum RefreshOutcome {
        NEVER,
        SUCCESS,
        FAILURE
    }

    /**
     * 目录健康状态投影。
     *
     * @param toolCount 当前缓存工具数
     * @param fresh 是否仍处于最大新鲜期限内
     * @param lastRefreshOutcome 最近刷新结果
     * @param lastRefreshAttemptAt 最近尝试时间
     * @param lastSuccessfulRefreshAt 最近成功时间
     * @param lastFailedRefreshAt 最近失败时间
     * @param lastFailureCode 最近跨服务失败码
     * @param freshUntil 旧目录新鲜期截止点
     * @param nextRefreshAt 下次允许刷新时间
     */
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

    /** 供网关并发读取并整体替换的内部目录状态。 */
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
