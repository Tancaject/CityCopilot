package com.citycopilot.agent.ops.v2;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 从已提交 Case 采样最终修复结果，接收消息或写工具成功不等同于修复完成。 */
@Component
@ConditionalOnProperty(prefix = "citycopilot.ops-v2", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpsCaseMetrics {
    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot = new Snapshot(
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);

    public OpsCaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("citycopilot.ops.case.pending", this, value -> value.snapshot.pending())
                .description("Committed cases in automatic processing or retry states").register(registry);
        Gauge.builder("citycopilot.ops.case.pending.oldest.age", this, value -> value.snapshot.oldestAge())
                .baseUnit("seconds").description("Time since creation of the oldest automatically pending case").register(registry);
        Gauge.builder("citycopilot.ops.case.manual.required", this, value -> value.snapshot.manualRequired())
                .description("Cases in MANUAL_REVIEW or FAILED, excluding routine approval waits").register(registry);
        Gauge.builder("citycopilot.ops.case.resolution.recent.succeeded", this, value -> value.snapshot.succeeded())
                .description("Cases successfully resolved in the last 15 minutes; cancellations excluded").register(registry);
        Gauge.builder("citycopilot.ops.case.resolution.recent.mean.duration", this, value -> value.snapshot.meanDuration())
                .baseUnit("seconds").description("Mean creation-to-resolution row timestamp duration, including human approval waits").register(registry);
        Gauge.builder("citycopilot.ops.case.resolution.recent.max.duration", this, value -> value.snapshot.maxDuration())
                .baseUnit("seconds").description("Maximum creation-to-resolution row timestamp duration, including human approval waits").register(registry);
        Gauge.builder("citycopilot.ops.case.sample.timestamp", this, value -> value.snapshot.sampleTime())
                .baseUnit("seconds").description("Database Unix timestamp of the last successful case sample").register(registry);
    }

    @Scheduled(fixedDelay = 15_000)
    public void refresh() {
        // 一个数据库快照内区分后台积压、人工处置与最终成功；失败时保留旧样本并暴露失鲜。
        snapshot = jdbc.queryForObject("""
                SELECT backlog.pending, backlog.oldest_age, backlog.manual_required,
                       recent.succeeded, recent.mean_duration, recent.max_duration,
                       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) AS sample_time
                FROM (
                    SELECT COALESCE(SUM(status NOT IN ('MANUAL_REVIEW','FAILED')),0) AS pending,
                           COALESCE(GREATEST(0, TIMESTAMPDIFF(MICROSECOND,
                               MIN(CASE WHEN status NOT IN ('MANUAL_REVIEW','FAILED') THEN created_at END),
                               CURRENT_TIMESTAMP(3))),0) / 1000000.0 AS oldest_age,
                           COALESCE(SUM(status IN ('MANUAL_REVIEW','FAILED')),0) AS manual_required
                    FROM ops_case
                    WHERE status IN ('OPEN','COLLECTING_EVIDENCE','PLAN_READY','APPROVED',
                                     'EXECUTING','VERIFYING','RETRY_WAIT','MANUAL_REVIEW','FAILED')
                ) backlog
                CROSS JOIN (
                    SELECT COUNT(*) AS succeeded,
                           AVG(CASE WHEN resolved_at>=created_at
                               THEN TIMESTAMPDIFF(MICROSECOND,created_at,resolved_at) / 1000000.0 END) AS mean_duration,
                           MAX(CASE WHEN resolved_at>=created_at
                               THEN TIMESTAMPDIFF(MICROSECOND,created_at,resolved_at) / 1000000.0 END) AS max_duration
                    FROM ops_case
                    WHERE status='SUCCEEDED'
                      AND resolved_at>=TIMESTAMPADD(MINUTE,-15,CURRENT_TIMESTAMP(3))
                      AND resolved_at<=CURRENT_TIMESTAMP(3)
                ) recent
                """, (rs, row) -> new Snapshot(
                rs.getDouble("pending"), rs.getDouble("oldest_age"), rs.getDouble("manual_required"),
                rs.getDouble("succeeded"),
                rs.getObject("mean_duration") == null ? Double.NaN : rs.getDouble("mean_duration"),
                rs.getObject("max_duration") == null ? Double.NaN : rs.getDouble("max_duration"),
                rs.getDouble("sample_time")));
    }

    private record Snapshot(double pending, double oldestAge, double manualRequired, double succeeded,
                            double meanDuration, double maxDuration, double sampleTime) {}
}
