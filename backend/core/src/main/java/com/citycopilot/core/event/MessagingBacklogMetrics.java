package com.citycopilot.core.event;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

import static com.citycopilot.core.utils.RedisConstants.SECKILL_DEAD_LETTER_STREAM;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_ORDER_STREAM;

@Component
public class MessagingBacklogMetrics {
    private static final String EVENT_AUDIT_GROUP = "citycopilot-event-audit-v1";

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong streamEntries = new AtomicLong();
    private final AtomicLong streamPending = new AtomicLong();
    private final AtomicLong streamDeadLetters = new AtomicLong();
    private final AtomicLong eventAuditLag = new AtomicLong();

    public MessagingBacklogMetrics(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        gauge(registry, "citycopilot.seckill.stream.entries", streamEntries);
        gauge(registry, "citycopilot.seckill.stream.pending", streamPending);
        gauge(registry, "citycopilot.seckill.stream.dead_letters", streamDeadLetters);
        gauge(registry, "citycopilot.rocketmq.event_audit.lag", eventAuditLag);
    }

    @Scheduled(fixedDelayString = "${citycopilot.messaging.metrics.interval-ms:10000}")
    public void refresh() {
        streamEntries.set(value(redisTemplate.opsForStream().size(SECKILL_ORDER_STREAM)));
        streamDeadLetters.set(value(redisTemplate.opsForStream().size(SECKILL_DEAD_LETTER_STREAM)));
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(SECKILL_ORDER_STREAM, "g1");
            streamPending.set(summary == null ? 0 : summary.getTotalPendingMessages());
        } catch (RuntimeException missingGroup) {
            streamPending.set(0);
        }
        Long published = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status='PUBLISHED'",
                Long.class
        );
        Long processed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE consumer_group=? AND status IN ('PROCESSED','STALE','QUARANTINED')",
                Long.class,
                EVENT_AUDIT_GROUP
        );
        eventAuditLag.set(Math.max(0, value(published) - value(processed)));
    }

    private void gauge(MeterRegistry registry, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get).register(registry);
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }
}
