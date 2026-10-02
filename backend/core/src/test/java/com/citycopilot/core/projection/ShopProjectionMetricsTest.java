package com.citycopilot.core.projection;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopProjectionMetricsTest {
    @Test
    void exportsBacklogAndFreshnessWithoutReportingFailedSamplesAsEmpty() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getDouble("backlog")).thenReturn(3.0, 0.0);
        when(row.getDouble("oldest_age")).thenReturn(120.5, 0.0);
        when(row.getDouble("sample_time")).thenReturn(1750000000.0, 1750000030.0);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(call -> call.<RowMapper<?>>getArgument(1).mapRow(row, 0))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenAnswer(call -> call.<RowMapper<?>>getArgument(1).mapRow(row, 0));
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            ShopProjectionMetrics metrics = new ShopProjectionMetrics(jdbc, registry);
            assertThat(registry.get("citycopilot.shop.projection.backlog").gauge().value()).isNaN();
            metrics.refresh();
            assertThat(registry.scrape())
                    .contains("citycopilot_shop_projection_backlog 3.0")
                    .contains("citycopilot_shop_projection_oldest_age_seconds 120.5");
            double sampledAt = registry.get("citycopilot.shop.projection.sample.timestamp").gauge().value();
            assertThat(sampledAt).isEqualTo(1750000000.0);
            assertThatThrownBy(metrics::refresh).hasMessage("database unavailable");
            assertThat(registry.get("citycopilot.shop.projection.backlog").gauge().value()).isEqualTo(3);
            assertThat(registry.get("citycopilot.shop.projection.sample.timestamp").gauge().value()).isEqualTo(sampledAt);
            metrics.refresh();
            assertThat(registry.get("citycopilot.shop.projection.backlog").gauge().value()).isZero();
            assertThat(registry.get("citycopilot.shop.projection.oldest.age").gauge().value()).isZero();
            assertThat(registry.get("citycopilot.shop.projection.sample.timestamp").gauge().value()).isGreaterThan(sampledAt);
        } finally {
            registry.close();
        }
    }
}
