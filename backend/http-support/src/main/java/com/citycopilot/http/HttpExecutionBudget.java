package com.citycopilot.http;

import java.time.Duration;

/** The deadline/cancellation portion of a request budget. */
public interface HttpExecutionBudget {
    Duration remaining();
    /** The logical absolute deadline propagated unchanged to every downstream hop. */
    long deadlineEpochMillis();
    void check();
    Cancellation onCancel(Runnable action);

    @FunctionalInterface
    interface Cancellation extends AutoCloseable {
        @Override void close();
    }
}
