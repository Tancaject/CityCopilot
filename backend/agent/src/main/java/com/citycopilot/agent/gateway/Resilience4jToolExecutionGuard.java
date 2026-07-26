package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.ToolMetadata;
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

@Component
public class Resilience4jToolExecutionGuard implements ToolExecutionGuard {
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, Retry> retries = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(
            4,
            16,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            daemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    @Override
    public Object execute(ToolMetadata tool, Callable<Object> invocation) throws Exception {
        RateLimiter rateLimiter = rateLimiters.computeIfAbsent(tool.name(), ignored -> rateLimiter(tool));
        if (!rateLimiter.acquirePermission()) {
            throw new ToolExecutionException("rate_limited", "Tool rate limit exceeded: " + tool.name(), true);
        }

        Callable<Object> guarded = CircuitBreaker.decorateCallable(
                circuitBreakers.computeIfAbsent(tool.name(), CircuitBreaker::ofDefaults),
                invocation
        );
        Callable<Object> timed = () -> callWithTimeout(tool, guarded);
        if (!tool.idempotent()) {
            return timed.call();
        }
        return Retry.decorateCallable(
                retries.computeIfAbsent(tool.name(), this::retry),
                timed
        ).call();
    }

    private Object callWithTimeout(ToolMetadata tool, Callable<Object> guarded) throws Exception {
        Future<Object> future;
        try {
            future = executor.submit(guarded);
        } catch (RejectedExecutionException rejected) {
            throw new ToolExecutionException("tool_overloaded", "Tool executor is overloaded: " + tool.name(), true);
        }
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ToolExecutionException("tool_timeout", "Tool timed out: " + tool.name(), true);
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
