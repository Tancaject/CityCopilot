package com.citycopilot.http;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A fixed cross-service deadline, measured with a monotonic clock within this process. */
public final class RequestDeadline implements HttpExecutionBudget {
    private static final ThreadLocal<RequestDeadline> CURRENT = new ThreadLocal<>();
    private final long deadlineNanos;
    private final long deadlineEpochMillis;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger bindings = new AtomicInteger();
    private final Set<Runnable> cancellations = ConcurrentHashMap.newKeySet();

    private RequestDeadline(Duration maximum, String inheritedHeader) {
        if (maximum.isNegative() || maximum.isZero()) throw new IllegalArgumentException("Request deadline must be positive");
        long nowMillis = System.currentTimeMillis();
        long remainingMillis = maximum.toMillis();
        if (inheritedHeader != null && !inheritedHeader.isBlank()) {
            long inherited;
            try { inherited = Long.parseLong(inheritedHeader); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid " + DeadlineHttpRequestFactory.DEADLINE_HEADER, error); }
            // Compare before subtracting so an arbitrary incoming long cannot overflow the budget.
            remainingMillis = inherited <= nowMillis ? 0 : Math.min(remainingMillis, inherited - nowMillis);
        }
        RequestDeadline previous = CURRENT.get();
        if (previous != null) remainingMillis = Math.min(remainingMillis, previous.remaining().toMillis());
        this.deadlineEpochMillis = Math.addExact(nowMillis, remainingMillis);
        this.deadlineNanos = System.nanoTime() + Duration.ofMillis(remainingMillis).toNanos();
    }

    public static RequestDeadline current() { return CURRENT.get(); }
    public static RequestDeadline create(Duration maximum, String inheritedHeader) {
        return new RequestDeadline(maximum, inheritedHeader);
    }
    public static Scope open(Duration maximum, String inheritedHeader) { return create(maximum, inheritedHeader).bind(); }
    public long deadlineEpochMillis() { return deadlineEpochMillis; }

    /** Explicit binding is also used when an MCP SDK callback changes threads. */
    public Scope bind() {
        RequestDeadline previous = CURRENT.get();
        bindings.incrementAndGet();
        CURRENT.set(this);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                if (bindings.decrementAndGet() == 0) cancel();
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        };
    }

    @Override public Duration remaining() {
        check();
        return Duration.ofNanos(Math.max(1, deadlineNanos - System.nanoTime()));
    }
    @Override public void check() {
        if (System.nanoTime() >= deadlineNanos || Thread.currentThread().isInterrupted()) cancel();
        if (cancelled.get()) throw new DeadlineExceededException();
    }
    @Override public Cancellation onCancel(Runnable action) {
        cancellations.add(action);
        if (cancelled.get()) action.run();
        return () -> cancellations.remove(action);
    }
    private void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancellations.forEach(action -> { try { action.run(); } catch (RuntimeException ignored) { } });
            cancellations.clear();
        }
    }

    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
    public static final class DeadlineExceededException extends RuntimeException {
        public DeadlineExceededException() { super("Request deadline exceeded; query the result before retrying a submitted write with its original idempotency key"); }
    }
}
