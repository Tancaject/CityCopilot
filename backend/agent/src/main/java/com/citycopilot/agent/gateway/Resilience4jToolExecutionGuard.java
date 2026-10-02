package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.budget.AgentBudget;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Resilience4j 的工具调用保护器。
 *
 * <p>按工具名隔离限流器、熔断器和重试器，并在有界线程池中执行超时控制；只有元数据判定为幂等的调用才自动重试。</p>
 */
@Component
public class Resilience4jToolExecutionGuard implements ToolExecutionGuard {
    /** 每个工具独立的分钟级限流器。 */
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    /** 每个工具独立的熔断状态，防止一个下游拖垮其他工具。 */
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    /** 仅供幂等工具使用的短重试策略。 */
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    /** 承载超时隔离的有界工作池，队列满时快速拒绝。 */
    private final ExecutorService executor = new ThreadPoolExecutor(
            4,
            16,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            daemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    /**
     * 依次应用限流、熔断、超时，并仅对幂等调用应用重试。
     *
     * @throws ToolExecutionException 限流、过载、超时或熔断时抛出可分类异常
     */
    @Override
    public Object execute(ToolMetadata tool, Callable<Object> invocation) throws Exception {
        AgentBudget.checkCurrent();
        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(tool.name(), ignored -> rateLimiter(tool));
        if (!rateLimiter.acquirePermission()) {
            throw new ToolExecutionException("rate_limited", "Tool rate limit exceeded: " + tool.name(), true);
        }

        Callable<Object> guarded = CircuitBreaker.decorateCallable(
                circuitBreakers.computeIfAbsent(tool.name(), CircuitBreaker::ofDefaults),
                invocation
        );
        Callable<Object> timed = () -> callWithTimeout(tool, guarded);
        // 非幂等工具绝不自动重试，避免网络边界不明时重复产生副作用。
        if (!tool.idempotent()) {
            return timed.call();
        }
        return Retry.decorateCallable(
                retries.computeIfAbsent(tool.name(), this::retry),
                timed
        ).call();
    }

    /** 在有界线程池中执行调用并把基础设施异常转换为统一工具错误。 */
    private Object callWithTimeout(ToolMetadata tool, Callable<Object> guarded) throws Exception {
        AgentBudget budget = AgentBudget.current();
        if (budget != null) budget.check();
        Future<Object> future;
        try {
            future = executor.submit(() -> {
                if (budget == null) return guarded.call();
                try (AgentBudget.Scope ignored = budget.bind()) {
                    budget.attempt();
                    return guarded.call();
                }
            });
        } catch (RejectedExecutionException rejected) {
            throw new ToolExecutionException("tool_overloaded", "Tool executor is overloaded: " + tool.name(), true);
        }
        try {
            long timeout = budget == null ? TimeUnit.SECONDS.toNanos(3)
                    : Math.min(TimeUnit.SECONDS.toNanos(3), budget.remaining().toNanos());
            return future.get(timeout, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            if (budget != null) budget.cancel("agent_tool_timeout");
            AgentBudget.checkCurrent();
            throw new ToolExecutionException("tool_timeout", "Tool timed out: " + tool.name(), true);
        } catch (InterruptedException e) {
            future.cancel(true);
            if (budget != null) { budget.cancel("agent_cancelled"); budget.check(); }
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CallNotPermittedException) {
                throw new ToolExecutionException("circuit_open", "Tool circuit is open: " + tool.name(), true);
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new ToolExecutionException("tool_error", cause.getMessage(), false);
        }
    }

    /** 容器关闭时取消仍在等待的工具任务，避免线程泄漏。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private RateLimiter rateLimiter(ToolMetadata tool) {
        int limit = tool.rateLimitPerMinute() <= 0 ? 60 : tool.rateLimitPerMinute();
        return RateLimiter.of(
                tool.name(),
                RateLimiterConfig.custom()
                        .limitForPeriod(limit)
                        .limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO)
                        .build()
        );
    }

    private Retry retry(String name) {
        return Retry.of(
                name,
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(100))
                        .retryOnException(error ->
                                error instanceof ToolExecutionException toolError && toolError.retryable()
                        )
                        .build()
        );
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "agent-tool-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
