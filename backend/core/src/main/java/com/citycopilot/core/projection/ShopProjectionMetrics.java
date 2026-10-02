package com.citycopilot.core.projection;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立采样持久化积压，消费停滞时仍能观测；抓取指标不占用数据库连接。 */
@Component
@ConditionalOnProperty(prefix = "citycopilot.projection.roles", name = "projector-enabled",
        havingValue = "true", matchIfMissing = true)
public class ShopProjectionMetrics {
    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot = new Snapshot(Double.NaN, Double.NaN, 0);

    public ShopProjectionMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("citycopilot.shop.projection.backlog", this, value -> value.snapshot.backlog())
                .description("Committed shop changes not yet projected to both search and GEO").register(registry);
        Gauge.builder("citycopilot.shop.projection.oldest.age", this, value -> value.snapshot.oldestAge())
                .baseUnit("seconds").description("Age of the oldest unprocessed shop change").register(registry);
        Gauge.builder("citycopilot.shop.projection.sample.timestamp", this, value -> value.snapshot.sampleTime())
                .baseUnit("seconds").description("Unix timestamp of the last successful backlog sample").register(registry);
    }

    @Scheduled(fixedDelayString = "${citycopilot.projection.metrics-refresh-ms:15000}")
    public void refresh() {
        // DB 时钟同时计算年龄与采样时间；查询失败保留旧样本，由 sample.timestamp 暴露失鲜。
        Snapshot next = jdbc.queryForObject("""
                SELECT COUNT(*) AS backlog,
                       COALESCE(GREATEST(0, TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(3))), 0) / 1000000.0 AS oldest_age,
                       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) AS sample_time
                FROM shop_projection_outbox
                WHERE status IN ('READY','FAILED','CLAIMED','DEAD')
                """, (rs, row) -> new Snapshot(rs.getDouble("backlog"), rs.getDouble("oldest_age"),
                rs.getDouble("sample_time")));
        snapshot = next;
    }

    private record Snapshot(double backlog, double oldestAge, double sampleTime) {}
}
