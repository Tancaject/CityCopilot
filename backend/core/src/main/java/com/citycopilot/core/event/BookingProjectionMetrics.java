package com.citycopilot.core.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 成功样本只来自业务事务提交回调；接收尝试不代表投影已提交。 */
@Component
public class BookingProjectionMetrics {
    private final MeterRegistry registry;
    private final Map<InboxOutcome, Counter> outcomes = new EnumMap<>(InboxOutcome.class);
    private final Counter failures;
    private final Counter clockSkew;
    private final Timer delay;
    private volatile boolean active;
    private volatile double lastReceipt;
    private volatile Observation observation = new Observation(0, Double.NaN);

    public BookingProjectionMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (InboxOutcome outcome : InboxOutcome.values()) {
            outcomes.put(outcome, registry.counter("citycopilot.booking.business.consume", "outcome",
                    outcome.name().toLowerCase(Locale.ROOT)));
        }
        failures = registry.counter("citycopilot.booking.business.consume", "outcome", "failure");
        clockSkew = registry.counter("citycopilot.booking.projection.clock.skew");
        delay = Timer.builder("citycopilot.booking.projection.delay")
                .description("Event occurrence to successful business projection transaction commit callback")
                .register(registry);
        Gauge.builder("citycopilot.booking.projection.consumer.active", this, value -> value.active ? 1 : 0)
                .description("Local BookingEvent consumer was started and has not been closed").register(registry);
        Gauge.builder("citycopilot.booking.projection.last.receipt", this, value -> value.lastReceipt)
                .baseUnit("seconds").description("Local Unix time of the most recent BookingEvent delivery attempt").register(registry);
        Gauge.builder("citycopilot.booking.projection.last.commit", this, value -> value.observation.committedAt())
                .baseUnit("seconds").description("Local Unix time of the last newly committed booking business event; zero means unobserved").register(registry);
        Gauge.builder("citycopilot.booking.projection.last.delay", this, value -> value.observation.delay())
                .baseUnit("seconds").description("Occurrence-to-commit delay of the last new projection; NaN means unobserved or clock skew").register(registry);
    }

    void active(boolean active) { this.active = active; }
    void received() { lastReceipt = registry.config().clock().wallTime() / 1000.0; }
    void failed() { failures.increment(); }

    public void classifiedAfterCommit(InboxOutcome outcome, Instant occurredAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Booking projection metrics require the business transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                outcomes.get(outcome).increment();
                // STALE 仍会新增历史业务事实；重复与隔离没有新的业务投影。
                if (outcome != InboxOutcome.PROCESSED && outcome != InboxOutcome.STALE) return;
                long committedAt = registry.config().clock().wallTime();
                long elapsed = committedAt - occurredAt.toEpochMilli();
                if (elapsed < 0) {
                    clockSkew.increment();
                    observation = new Observation(committedAt / 1000.0, Double.NaN);
                } else {
                    delay.record(elapsed, TimeUnit.MILLISECONDS);
                    observation = new Observation(committedAt / 1000.0, elapsed / 1000.0);
                }
            }
        });
    }

    private record Observation(double committedAt, double delay) {}
}
