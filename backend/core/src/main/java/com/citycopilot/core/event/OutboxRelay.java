package com.citycopilot.core.event;

import com.citycopilot.core.config.RocketMqProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OutboxRelay {
    private final OutboxRepository repository;
    private final DomainEventPublisher publisher;
    private final RocketMqProperties properties;
    private MeterRegistry meterRegistry;

    public OutboxRelay(
            OutboxRepository repository,
            DomainEventPublisher publisher,
            RocketMqProperties properties
    ) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        meterRegistry = registry;
        Gauge.builder("citycopilot.outbox.unpublished", repository, OutboxRepository::readyCount)
                .description("Ready, failed, or claimed Outbox records")
                .register(registry);
        Gauge.builder("citycopilot.outbox.dead", repository, OutboxRepository::deadCount)
                .description("Outbox records requiring operator intervention")
                .register(registry);
        Gauge.builder("citycopilot.outbox.oldest_ready_age_seconds", repository,
                        OutboxRepository::oldestReadyAgeSeconds)
                .description("Age of the oldest unpublished Outbox record")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${citycopilot.rocketmq.relay-interval-ms:1000}")
    public void relay() {
        if (!properties.isEnabled() || !properties.isProducerEnabled()) {
            return;
        }
        for (OutboxRecord record : repository.claimReady(
                properties.getRelayBatchSize(),
                properties.getClaimLease()
        )) {
            publishOne(record);
        }
    }

    private void publishOne(OutboxRecord record) {
        long started = System.nanoTime();
        try {
            publisher.publish(record);
            if (!repository.markPublished(record)) {
                throw new IllegalStateException("Outbox fencing token is no longer current");
            }
            count("citycopilot.rocketmq.publish", "result", "success");
        } catch (Exception error) {
            Duration backoff = properties.getRetryBackoff().multipliedBy(
                    Math.max(1, Math.min(record.attempts(), 12))
            );
            repository.markFailed(record, error.getMessage(), backoff, properties.getMaxAttempts());
            count("citycopilot.rocketmq.publish", "result", "failure");
        } finally {
            if (meterRegistry != null) {
                Timer.builder("citycopilot.rocketmq.publish.duration")
                        .register(meterRegistry)
                        .record(Duration.ofNanos(System.nanoTime() - started));
            }
        }
    }

    private void count(String name, String tagName, String tagValue) {
        if (meterRegistry != null) {
            Counter.builder(name).tag(tagName, tagValue).register(meterRegistry).increment();
        }
    }
}
