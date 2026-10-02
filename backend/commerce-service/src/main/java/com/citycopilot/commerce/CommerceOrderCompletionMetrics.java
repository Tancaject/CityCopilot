package com.citycopilot.commerce;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 从已提交账本采样，重试、事务回滚和进程重启不产生额外完成次数。 */
@Component
public class CommerceOrderCompletionMetrics {
    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot = new Snapshot(
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);

    public CommerceOrderCompletionMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("citycopilot.commerce.order.pending", this, value -> value.snapshot.pending())
                .description("All committed ACCEPTED admissions awaiting an order").register(registry);
        Gauge.builder("citycopilot.commerce.order.pending.oldest.age", this, value -> value.snapshot.oldestAge())
                .baseUnit("seconds").description("Age of the oldest committed ACCEPTED admission").register(registry);
        Gauge.builder("citycopilot.commerce.order.repair.required", this, value -> value.snapshot.repairRequired())
                .description("All admissions requiring operator repair").register(registry);
        Gauge.builder("citycopilot.commerce.order.completion.recent.admitted", this, value -> value.snapshot.admitted())
                .description("Admissions accepted in the last 15 minutes, excluding legacy order imports").register(registry);
        Gauge.builder("citycopilot.commerce.order.completion.recent.persisted", this, value -> value.snapshot.created())
                .description("Committed orders within the recent admitted cohort").register(registry);
        Gauge.builder("citycopilot.commerce.order.completion.recent.mean.duration", this, value -> value.snapshot.meanDuration())
                .baseUnit("seconds").description("Mean acceptance-to-order-row timestamp duration for completed recent admissions").register(registry);
        Gauge.builder("citycopilot.commerce.order.completion.recent.max.duration", this, value -> value.snapshot.maxDuration())
                .baseUnit("seconds").description("Maximum acceptance-to-order-row timestamp duration for completed recent admissions").register(registry);
        Gauge.builder("citycopilot.commerce.order.completion.sample.timestamp", this, value -> value.snapshot.sampleTime())
                .baseUnit("seconds").description("Database Unix timestamp of the last successful completion sample").register(registry);
    }

    @Scheduled(fixedDelay = 15_000)
    public void refresh() {
        // 一个语句使用同一数据库快照和时钟；采样失败保留旧值，由时间戳暴露失鲜。
        snapshot = jdbc.queryForObject("""
                SELECT backlog.pending, backlog.oldest_age, backlog.repair_required,
                       recent.admitted, recent.created, recent.mean_duration, recent.max_duration,
                       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) AS sample_time
                FROM (
                    SELECT COALESCE(SUM(status='ACCEPTED'),0) AS pending,
                           COALESCE(GREATEST(0, TIMESTAMPDIFF(MICROSECOND,
                               MIN(CASE WHEN status='ACCEPTED' THEN accepted_at END),
                               CURRENT_TIMESTAMP(3))),0) / 1000000.0 AS oldest_age,
                           COALESCE(SUM(status='REPAIR_REQUIRED'),0) AS repair_required
                    FROM commerce_admission_ledger
                    WHERE status IN ('ACCEPTED','REPAIR_REQUIRED')
                ) backlog
                CROSS JOIN (
                    SELECT COUNT(*) AS admitted,
                           COUNT(orders.id) AS created,
                           AVG(CASE WHEN orders.create_time>=ledger.accepted_at
                               THEN TIMESTAMPDIFF(MICROSECOND,ledger.accepted_at,orders.create_time) / 1000000.0 END) AS mean_duration,
                           MAX(CASE WHEN orders.create_time>=ledger.accepted_at
                               THEN TIMESTAMPDIFF(MICROSECOND,ledger.accepted_at,orders.create_time) / 1000000.0 END) AS max_duration
                    FROM commerce_admission_ledger ledger
                    LEFT JOIN commerce_order orders ON orders.id=ledger.order_id
                    WHERE ledger.accepted_at>=TIMESTAMPADD(MINUTE,-15,CURRENT_TIMESTAMP(3))
                      AND ledger.accepted_at<=CURRENT_TIMESTAMP(3)
                      AND ledger.routing_generation<>'legacy-order-v0'
                      AND ledger.status IN ('ACCEPTED','ORDER_CREATED','REPAIR_REQUIRED')
                ) recent
                """, (rs, row) -> new Snapshot(
                rs.getDouble("pending"), rs.getDouble("oldest_age"), rs.getDouble("repair_required"),
                rs.getDouble("admitted"), rs.getDouble("created"),
                rs.getObject("mean_duration") == null ? Double.NaN : rs.getDouble("mean_duration"),
                rs.getObject("max_duration") == null ? Double.NaN : rs.getDouble("max_duration"),
                rs.getDouble("sample_time")));
    }

    private record Snapshot(double pending, double oldestAge, double repairRequired, double admitted,
                            double created, double meanDuration, double maxDuration, double sampleTime) {}
}
