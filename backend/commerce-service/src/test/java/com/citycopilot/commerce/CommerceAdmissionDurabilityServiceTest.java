package com.citycopilot.commerce;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceAdmissionDurabilityServiceTest {
    private JdbcTemplate jdbc;
    private SimpleMeterRegistry metrics;
    private CommerceAdmissionDurabilityService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        metrics = new SimpleMeterRegistry();
        service = new CommerceAdmissionDurabilityService(
                jdbc,
                mock(CommerceOrderIdGenerator.class),
                new CommerceRedisKeyspace(4, "v7"),
                metrics,
                100,
                5_000,
                30_000,
                5_000,
                3,
                50_000,
                300_000,
                1_000,
                "test", mock(CommerceReadCache.class)
        );
    }

    @Test
    void acceptedZeroAffectedRowsIsAnIdempotentSuccessForSameIdentity() {
        when(jdbc.update(contains("SET status='ACCEPTED'"), any(Object[].class)))
                .thenReturn(0);
        when(jdbc.queryForList(
                contains("WHERE user_id=? AND idempotency_key=?"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(row("ACCEPTED", null, 0)));

        service.markAccepted(admission(CommerceAdmissionDurabilityService.Status.PREPARED, 0, null));

        assertThat(metrics.get("citycopilot.commerce.admission.backlog").gauge().value())
                .isZero();
    }

    @Test
    void terminalZeroAffectedRowsIsConfirmedFromTheSameOrderCreatedIdentity() {
        when(jdbc.update(contains("SET status='ORDER_CREATED'"), any(Object[].class)))
                .thenReturn(0);
        when(jdbc.queryForList(
                contains("WHERE user_id=? AND idempotency_key=?"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(row("ORDER_CREATED", null, 0)));

        service.markOrderCreated(
                admission(CommerceAdmissionDurabilityService.Status.ACCEPTED, 0, "claim-1")
        );
    }

    @Test
    void rejectedReplayReproducesOriginalCodeStatusAndMessage() {
        Map<String, Object> rejected = row("REJECTED", "SECKILL_STOCK_NOT_ENOUGH", 409);
        rejected.put("rejection_message", "Voucher stock exhausted");
        when(jdbc.queryForList(
                contains("WHERE user_id=? AND idempotency_key=?"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(rejected));

        assertThatThrownBy(() -> service.prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        )).isInstanceOfSatisfying(
                CommerceApiException.class,
                error -> {
                    org.assertj.core.api.Assertions.assertThat(error.code())
                            .isEqualTo("SECKILL_STOCK_NOT_ENOUGH");
                    org.assertj.core.api.Assertions.assertThat(error.status()).isEqualTo(409);
                    org.assertj.core.api.Assertions.assertThat(error.getMessage())
                            .isEqualTo("Voucher stock exhausted");
                }
        );
    }

    @Test
    void preparedIdentityFromAnotherKeyspaceConfigurationFailsClosed() {
        when(jdbc.queryForList(
                contains("WHERE user_id=? AND idempotency_key=?"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(row("PREPARED", null, 0, "v6", 2)));

        assertThatThrownBy(() -> service.prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        )).isInstanceOfSatisfying(
                CommerceApiException.class,
                error -> assertThat(error.code()).isEqualTo("COMMERCE_ADMISSION_ROUTING_CONFLICT")
        );
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void stalePreparedOwnerCannotAcceptAfterAnotherGenerationRebindsTheRow() {
        when(jdbc.update(contains("SET status='ACCEPTED'"), any(Object[].class)))
                .thenReturn(0);
        when(jdbc.queryForList(
                contains("WHERE user_id=? AND idempotency_key=?"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(row("PREPARED", null, 0, "v7", 4)));

        assertThatThrownBy(() -> service.markAccepted(new CommerceAdmissionDurabilityService.Admission(
                300L, 100L, 200L, "request-1", "seckill:v1:100",
                CommerceAdmissionDurabilityService.Status.PREPARED,
                null, 0, null, 0, null, "v6", 2
        ))).isInstanceOfSatisfying(
                CommerceApiException.class,
                error -> assertThat(error.code()).isEqualTo("COMMERCE_DURABLE_ADMISSION_UNAVAILABLE")
        );
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("routing_generation=?")
                                && sql.contains("physical_shard_count=?")
                                && sql.contains("status='PREPARED'")
                ),
                any(Object[].class)
        );
    }

    @Test
    void newPreparedIdentityNeverQueriesTheOrderFactTable() {
        when(jdbc.queryForList(
                contains("FROM commerce_admission_ledger"),
                eq(200L),
                eq("request-1")
        )).thenReturn(
                List.of(),
                List.of(row("PREPARED", null, 0))
        );
        when(jdbc.queryForMap(contains("accepted_backlog"))).thenReturn(Map.of(
                "accepted_backlog", 0L,
                "oldest_accepted_age_ms", 0L,
                "repair_required", 0L
        ));

        CommerceAdmissionDurabilityService.Admission prepared = service.prepare(
                100L, 200L, "request-1", "seckill:v1:100", 300L
        );

        assertThat(prepared.status()).isEqualTo(CommerceAdmissionDurabilityService.Status.PREPARED);
        verify(jdbc).update(contains("INSERT IGNORE INTO commerce_admission_ledger"), any(Object[].class));
        verify(jdbc, never()).queryForList(contains("FROM commerce_order\n"), any(Object[].class));
    }

    @Test
    void claimFiltersToAcceptedRowsInTheLocalRoutingGeneration() {
        when(jdbc.update(contains("UPDATE commerce_admission_ledger"), any(Object[].class)))
                .thenReturn(0);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);

        service.claimAcceptedBatch();

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("status='ACCEPTED'")
                                && sql.contains("routing_generation=?")
                                && sql.contains("physical_shard_count=?")
                                && !sql.contains("PREPARED")
                ),
                arguments.capture()
        );
        assertThat(arguments.getValue()).hasSize(7);
        assertThat(arguments.getValue()[3]).isEqualTo("v7");
        assertThat(arguments.getValue()[4]).isEqualTo(4);
    }

    @Test
    void repairRequiredBacklogFailsNewPreparedAdmissionClosed() {
        when(jdbc.queryForList(
                contains("WHERE user_id=? AND idempotency_key=?"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(row("PREPARED", null, 0)));
        when(jdbc.queryForMap(contains("repair_required"))).thenReturn(Map.of(
                "accepted_backlog", 0L,
                "oldest_accepted_age_ms", 0L,
                "repair_required", 1L
        ));

        assertThatThrownBy(() -> service.prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        )).isInstanceOfSatisfying(
                CommerceApiException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.code())
                        .isEqualTo("COMMERCE_ADMISSION_BACKLOG_UNHEALTHY")
        );
        verify(jdbc, never()).update(contains("INSERT IGNORE"), any(Object[].class));
    }

    @Test
    void queueConfirmationDoesNotConsumeFailureAttemptBudget() {
        when(jdbc.update(contains("claimed_until=TIMESTAMPADD"), any(Object[].class)))
                .thenReturn(1);

        service.releaseAfterQueueConfirmation(
                admission(CommerceAdmissionDurabilityService.Status.ACCEPTED, 2, "claim-1")
        );

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("claimed_until=TIMESTAMPADD")
                                && !sql.contains("attempts=attempts+1")
                ),
                any(Object[].class)
        );
    }

    @Test
    void finalRecoveryFailureConsumesOneAttemptAndMovesOnlyAcceptedClaimToRepair() {
        when(jdbc.update(contains("SET status='REPAIR_REQUIRED'"), any(Object[].class)))
                .thenReturn(1);
        CommerceAdmissionDurabilityService.Admission claimed = admission(
                CommerceAdmissionDurabilityService.Status.ACCEPTED,
                2,
                "claim-1"
        );

        service.releaseForRetryOrRepair(claimed, new IllegalStateException("still missing"));

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("status='REPAIR_REQUIRED'")
                                && sql.contains("attempts=attempts+1")
                                && sql.contains("status='ACCEPTED'")
                                && sql.contains("claim_token=?")
                                && !sql.contains("PREPARED")
                ),
                any(Object[].class)
        );
    }

    @Test
    void operatorRequeueChangesOnlyTheRequestedRepairRequiredOrder() {
        when(jdbc.update(contains("WHERE order_id=? AND status='REPAIR_REQUIRED'"),
                any(Object[].class))).thenReturn(1);

        service.requeueRepairRequired(300L);

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("SET status='ACCEPTED'")
                                && sql.contains("WHERE order_id=? AND status='REPAIR_REQUIRED'")
                                && !sql.contains("PREPARED")
                ),
                any(Object[].class)
        );
    }

    @Test
    void legacyStreamOrderWithoutLedgerIsMaterializedAsTerminalIdentity() {
        when(jdbc.queryForList(
                contains("FROM commerce_admission_ledger"),
                eq(200L),
                eq("request-1")
        )).thenReturn(
                List.of(),
                List.of(row("ORDER_CREATED", null, 0))
        );
        when(jdbc.queryForList(
                contains("FROM commerce_order"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(Map.of("id", 300L, "voucher_id", 100L)));
        when(jdbc.update(
                contains("INSERT INTO commerce_admission_ledger"),
                any(Object[].class)
        )).thenReturn(1);

        service.markOrderCreated(300L, 100L, 200L, "request-1");

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("INSERT INTO commerce_admission_ledger")
                                && sql.contains("'ORDER_CREATED'")
                                && !sql.contains("INSERT IGNORE")
                ),
                any(Object[].class)
        );
    }

    @Test
    void terminalizingPreparedOrderDoesNotDecrementAcceptedBacklogGauge() {
        when(jdbc.queryForMap(contains("accepted_backlog"))).thenReturn(Map.of(
                "accepted_backlog", 2L,
                "oldest_accepted_age_ms", 10L,
                "repair_required", 0L
        ));
        service.refreshCapacityMetrics();
        when(jdbc.queryForList(
                contains("FROM commerce_admission_ledger"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(row("PREPARED", null, 0)));
        when(jdbc.update(contains("AND status=?"), any(Object[].class))).thenReturn(1);

        service.markOrderCreated(300L, 100L, 200L, "request-1");

        assertThat(metrics.get("citycopilot.commerce.admission.backlog").gauge().value())
                .isEqualTo(2.0);
    }

    @Test
    void terminalizingAcceptedOrderDecrementsAcceptedBacklogGaugeOnce() {
        when(jdbc.queryForMap(contains("accepted_backlog"))).thenReturn(Map.of(
                "accepted_backlog", 2L,
                "oldest_accepted_age_ms", 10L,
                "repair_required", 0L
        ));
        service.refreshCapacityMetrics();
        when(jdbc.queryForList(
                contains("FROM commerce_admission_ledger"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(row("ACCEPTED", null, 0)));
        when(jdbc.update(contains("AND status=?"), any(Object[].class))).thenReturn(1);

        service.markOrderCreated(300L, 100L, 200L, "request-1");

        assertThat(metrics.get("citycopilot.commerce.admission.backlog").gauge().value())
                .isEqualTo(1.0);
    }

    private CommerceAdmissionDurabilityService.Admission admission(
            CommerceAdmissionDurabilityService.Status status,
            int attempts,
            String claimToken
    ) {
        return new CommerceAdmissionDurabilityService.Admission(
                300L, 100L, 200L, "request-1", "seckill:v1:100", status,
                null, 0, null, attempts, claimToken, "v7", 4
        );
    }

    private Map<String, Object> row(String status, String rejectionCode, int rejectionStatus) {
        return row(status, rejectionCode, rejectionStatus, "v7", 4);
    }

    private Map<String, Object> row(
            String status,
            String rejectionCode,
            int rejectionStatus,
            String generation,
            int shardCount
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_id", 300L);
        row.put("voucher_id", 100L);
        row.put("user_id", 200L);
        row.put("idempotency_key", "request-1");
        row.put("request_fingerprint", "seckill:v1:100");
        row.put("status", status);
        row.put("rejection_code", rejectionCode);
        row.put("rejection_http_status", rejectionStatus);
        row.put("rejection_message", null);
        row.put("attempts", 0);
        row.put("claim_token", null);
        row.put("routing_generation", generation);
        row.put("physical_shard_count", shardCount);
        return row;
    }
}
