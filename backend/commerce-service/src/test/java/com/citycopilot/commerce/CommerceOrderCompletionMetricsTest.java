package com.citycopilot.commerce;

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

class CommerceOrderCompletionMetricsTest {
    @Test
    void exportsCommittedSnapshotAndRetainsItsTimestampWhenSamplingFails() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getDouble("pending")).thenReturn(3.0);
        when(row.getDouble("oldest_age")).thenReturn(1200.5);
        when(row.getDouble("repair_required")).thenReturn(1.0);
        when(row.getDouble("admitted")).thenReturn(5.0);
        when(row.getDouble("created")).thenReturn(2.0);
        when(row.getObject("mean_duration")).thenReturn(10.0);
        when(row.getDouble("mean_duration")).thenReturn(10.0);
        when(row.getObject("max_duration")).thenReturn(15.0);
        when(row.getDouble("max_duration")).thenReturn(15.0);
        when(row.getDouble("sample_time")).thenReturn(1750000000.0);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(call -> call.<RowMapper<?>>getArgument(1).mapRow(row, 0))
                .thenThrow(new IllegalStateException("database unavailable"));
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new CommerceOrderCompletionMetrics(jdbc, registry);
            assertThat(registry.get("citycopilot.commerce.order.pending").gauge().value()).isNaN();
            assertThat(registry.get("citycopilot.commerce.order.completion.sample.timestamp").gauge().value()).isZero();
            metrics.refresh();
            assertThat(registry.scrape())
                    .contains("citycopilot_commerce_order_pending 3.0")
                    .contains("citycopilot_commerce_order_pending_oldest_age_seconds 1200.5")
                    .contains("citycopilot_commerce_order_repair_required 1.0")
                    .contains("citycopilot_commerce_order_completion_recent_admitted 5.0")
                    .contains("citycopilot_commerce_order_completion_recent_persisted 2.0")
                    .contains("citycopilot_commerce_order_completion_recent_mean_duration_seconds 10.0")
                    .contains("citycopilot_commerce_order_completion_recent_max_duration_seconds 15.0")
                    .contains("citycopilot_commerce_order_completion_sample_timestamp_seconds 1.75E9");
            assertThatThrownBy(metrics::refresh).hasMessage("database unavailable");
            assertThat(registry.get("citycopilot.commerce.order.completion.recent.persisted").gauge().value()).isEqualTo(2);
            assertThat(registry.get("citycopilot.commerce.order.completion.sample.timestamp").gauge().value()).isEqualTo(1750000000);
        } finally {
            registry.close();
        }
    }
}
