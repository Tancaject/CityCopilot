package com.citycopilot.http;

import java.time.Duration;

/** The deadline/cancellation portion shared by request deadlines and Agent execution budgets. */
public interface HttpExecutionBudget {
    Duration remaining();
    void check();
    Cancellation onCancel(Runnable action);

    @FunctionalInterface
    interface Cancellation extends AutoCloseable {
        @Override void close();
    }
}
