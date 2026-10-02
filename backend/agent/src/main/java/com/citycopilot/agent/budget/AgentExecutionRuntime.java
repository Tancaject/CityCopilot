package com.citycopilot.agent.budget;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded admission shared by interactive turns and Ops work units. */
@Component
public final class AgentExecutionRuntime implements AutoCloseable {
    private final AgentBudgetProperties limits;
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Semaphore capacity;
    private final ConcurrentHashMap<AgentBudget, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private MeterRegistry metrics;

    public AgentExecutionRuntime(AgentBudgetProperties limits) {
        this.limits = limits;
        capacity = new Semaphore(limits.getConcurrency());
        executor = new ThreadPoolExecutor(limits.getConcurrency(), limits.getConcurrency(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(limits.getQueueCapacity()), threads("agent-request-"), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, threads("agent-deadline-"));
        deadlines.setRemoveOnCancelPolicy(true);
    }

    private static class DirectUse { private static final AgentExecutionRuntime INSTANCE = new AgentExecutionRuntime(new AgentBudgetProperties()); }
    /** Direct-construction tests and non-Spring callers retain the same finite defaults. */
    public static AgentExecutionRuntime directUse() { return DirectUse.INSTANCE; }
    public AgentBudgetProperties limits() { return limits; }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        metrics = registry;
        Gauge.builder("citycopilot.agent.requests.active", capacity, value -> limits.getConcurrency() - value.availablePermits()).register(registry);
        Gauge.builder("citycopilot.agent.requests.queued", executor, value -> value.getQueue().size()).register(registry);
    }

    public AgentBudget newBudget() {
        AgentBudget budget = new AgentBudget(limits);
        budget.onCancel(() -> { if (metrics != null) metrics.counter("citycopilot.agent.budget.stopped", "reason", budget.stopReason()).increment(); });
        timers.put(budget, deadlines.schedule(() -> budget.cancel("agent_deadline_exceeded"),
                limits.getDeadline().toNanos(), TimeUnit.NANOSECONDS));
        return budget;
    }

    public <T> FutureTask<T> submit(AgentBudget budget, Callable<T> action) {
        return submit(budget, action, ignored -> { });
    }

    public <T> FutureTask<T> submit(AgentBudget budget, Callable<T> action,
                                  java.util.function.Consumer<AgentBudgetException> admissionFailure) {
        FutureTask<T> task = new FutureTask<>(() -> {
            try { return runAdmitted(budget, action); }
            catch (AgentBudgetException error) { admissionFailure.accept(error); throw error; }
            finally { release(budget); }
        });
        try {
            budget.check();
            budget.submitted(true);
            budget.onCancel(() -> { task.cancel(true); executor.remove(task); release(budget); });
            executor.execute(task);
            return task;
        } catch (RejectedExecutionException overloaded) {
            release(budget);
            throw overloaded();
        } catch (RuntimeException error) {
            release(budget);
            throw error;
        }
    }

    /** A synchronous confirmation gets execution capacity before its callable can consume approval. */
    public <T> T call(Callable<T> action) {
        AgentBudget budget = AgentBudget.current() == null ? newBudget() : AgentBudget.current();
        FutureTask<T> task = submit(budget, action);
        try { return task.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS); }
        catch (java.util.concurrent.TimeoutException error) {
            budget.cancel("agent_deadline_exceeded");
            task.cancel(true);
            executor.remove(task);
            release(budget);
            throw budget.failure("agent_deadline_exceeded");
        } catch (InterruptedException error) {
            budget.cancel("agent_cancelled");
            task.cancel(true);
            executor.remove(task);
            release(budget);
            throw budget.failure("agent_cancelled");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(error.getCause());
        } catch (java.util.concurrent.CancellationException error) {
            budget.check();
            throw budget.failure("agent_cancelled");
        }
    }

    /** Ops keeps its small role-specific pools while sharing the process-wide active limit. */
    public <T> T runInline(AgentBudget budget, Callable<T> action) {
        try { return runAdmitted(budget, action); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(error); }
        finally { release(budget); }
    }

    private <T> T runAdmitted(AgentBudget budget, Callable<T> action) throws Exception {
        try (AgentBudget.Scope ignored = budget.bind()) {
            budget.check();
            if (!capacity.tryAcquire()) throw overloaded();
            try { budget.check(); return action.call(); }
            finally { capacity.release(); }
        }
    }

    public void release(AgentBudget budget) {
        ScheduledFuture<?> timer = timers.remove(budget);
        if (timer != null) timer.cancel(false);
        budget.close();
    }

    private static AgentBudgetException overloaded() {
        return new AgentBudgetException("agent_overloaded", "Agent 当前处理容量已满，本次操作尚未开始，请稍后重试。", false);
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> { Thread thread = new Thread(task, prefix + sequence.incrementAndGet()); thread.setDaemon(true); return thread; };
    }

    @PreDestroy public void close() {
        timers.keySet().forEach(budget -> budget.cancel("agent_cancelled"));
        executor.shutdownNow();
        deadlines.shutdownNow();
    }
}
