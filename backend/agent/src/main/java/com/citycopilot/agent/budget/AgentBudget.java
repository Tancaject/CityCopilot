package com.citycopilot.agent.budget;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.citycopilot.http.HttpExecutionBudget;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** One request's budget, explicitly carried across the Agent's two executor boundaries. */
public final class AgentBudget implements HttpExecutionBudget, AutoCloseable {
    private static final ThreadLocal<AgentBudget> CURRENT = new ThreadLocal<>();
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final AgentBudgetProperties limits;
    private final long deadlineNanos;
    private final AtomicReference<String> stopped = new AtomicReference<>();
    private final Object threadLock = new Object();
    private final Set<Thread> threads = new java.util.HashSet<>();
    private final Set<Runnable> cancellation = ConcurrentHashMap.newKeySet();
    private final AtomicInteger models = new AtomicInteger();
    private final AtomicInteger tools = new AtomicInteger();
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger plans = new AtomicInteger();
    private final AtomicInteger reservedOutputTokens = new AtomicInteger();
    private final AtomicInteger responseBytes = new AtomicInteger();
    private volatile boolean writeOccurred;
    private final AtomicInteger writesInFlight = new AtomicInteger();
    private volatile boolean submitted;
    private volatile boolean finished;

    public AgentBudget(AgentBudgetProperties limits) {
        this.limits = limits;
        this.deadlineNanos = System.nanoTime() + limits.getDeadline().toNanos();
    }

    public static AgentBudget current() { return CURRENT.get(); }
    public AgentBudgetProperties limits() { return limits; }
    public boolean submitted() { return submitted; }
    public void submitted(boolean value) { submitted = value; }
    public String stopReason() { return stopped.get(); }
    public void finish() { finished = true; }
    public boolean finished() { return finished; }
    public static void checkCurrent() { if (current() != null) current().check(); }

    public Scope bind() {
        AgentBudget previous = CURRENT.get();
        Thread thread = Thread.currentThread();
        CURRENT.set(this);
        boolean added;
        synchronized (threadLock) { added = threads.add(thread); }
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            synchronized (threadLock) {
                if (added) threads.remove(thread);
                // Serialize unregister/clear with cancellation so a reused worker cannot be interrupted later.
                if (stopped.get() != null) Thread.interrupted();
            }
        };
    }

    public void check() {
        if (System.nanoTime() >= deadlineNanos) cancel("agent_deadline_exceeded");
        if (Thread.currentThread().isInterrupted() && stopped.get() == null) cancel("agent_cancelled");
        if (stopped.get() != null) throw failure(stopped.get());
    }

    public Duration remaining() {
        check();
        return Duration.ofNanos(Math.max(1, deadlineNanos - System.nanoTime()));
    }

    public void cancel(String reason) {
        if (stopped.compareAndSet(null, reason)) {
            synchronized (threadLock) {
                threads.forEach(thread -> { if (thread != Thread.currentThread()) thread.interrupt(); });
            }
            java.util.List.copyOf(cancellation).forEach(action -> { try { action.run(); } catch (RuntimeException ignored) { } });
        }
    }

    public Scope onCancel(Runnable action) {
        cancellation.add(action);
        if (stopped.get() != null) action.run();
        return () -> cancellation.remove(action);
    }

    public AgentBudgetException failure(String code) {
        String message = switch (code) {
            case "agent_deadline_exceeded" -> "本轮 Agent 请求已超过截止时间，请缩小任务后重试。";
            case "agent_cancelled" -> "本轮 Agent 请求已取消。";
            case "agent_tool_timeout" -> "本轮 Agent 工具调用超时，后续步骤已停止。";
            case "agent_tool_catalog_too_large" -> "Agent 从 Core 读取的工具目录超过元数据上限，规划已停止；请联系管理员检查工具 Schema 大小及 context-bytes 配置。";
            case "agent_write_outcome_unknown" -> "Agent 未能取得写调用的明确结果，后续步骤已停止。";
            default -> "本轮 Agent 请求达到资源上限（" + code + "），请缩小输入或任务范围。";
        };
        if (writeOutcomeUnknown()) message += "写操作可能已经提交，结果未知；请先查询业务状态，不要重新发起写入。";
        else if (writeOccurred) message += "本轮已完成的写操作不会撤销，请查询业务状态后继续。";
        return new AgentBudgetException(code, message, writeOutcomeUnknown());
    }

    public void reject(String code) {
        cancel(code);
        throw failure(code);
    }

    public void message(String value) { bytes(value, limits.getMessageBytes(), "agent_message_too_large"); }
    public void context(Object value) { bytes(json(value), limits.getContextBytes(), "agent_context_too_large"); }
    public void toolResult(Object value) { bytes(json(value), limits.getToolResultBytes(), "agent_tool_result_too_large"); }
    public void response(Object value) {
        check();
        add(responseBytes, byteCount(json(value)), limits.getResponseBytes(), "agent_response_too_large");
    }

    public int model(Object request) {
        context(request);
        // UTF-8 bytes are a conservative local token proxy, not provider usage accounting.
        bytes(json(request), limits.getModelInputTokens(), "agent_model_input_budget");
        add(models, 1, limits.getModelCalls(), "agent_model_calls_exceeded");
        add(reservedOutputTokens, limits.getModelOutputTokens(), limits.getTotalModelOutputTokens(), "agent_model_output_budget");
        return limits.getModelOutputTokens();
    }

    public void tool() { check(); add(tools, 1, limits.getToolCalls(), "agent_tool_calls_exceeded"); }
    public void attempt() { check(); add(attempts, 1, limits.getToolAttempts(), "agent_tool_attempts_exceeded"); }
    public void writeStarted() { check(); writeOccurred = true; writesInFlight.incrementAndGet(); }
    public void writeCompleted() { writesInFlight.set(0); }
    public boolean writeOutcomeUnknown() { return writesInFlight.get() > 0; }
    public void plan(int size) {
        check();
        if (size > limits.getPlanSteps()) reject("agent_plan_too_large");
        add(plans, size, limits.getTotalPlanSteps(), "agent_plan_steps_exceeded");
    }

    public void bytes(String value, int limit, String code) {
        check();
        if (byteCount(value) > limit) reject(code);
    }

    private void add(AtomicInteger counter, int amount, int maximum, String code) {
        if (counter.addAndGet(amount) > maximum) reject(code);
    }

    private static int byteCount(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("Unable to measure Agent payload", error); }
    }

    /** Unwrap wrapped transport exceptions before any retry/fallback translation. */
    public static void rethrowIfStopped(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof AgentBudgetException budgetError) throw budgetError;
        }
        checkCurrent();
    }

    @Override public void close() { cancellation.clear(); }
    @FunctionalInterface public interface Scope extends HttpExecutionBudget.Cancellation { @Override void close(); }
}
