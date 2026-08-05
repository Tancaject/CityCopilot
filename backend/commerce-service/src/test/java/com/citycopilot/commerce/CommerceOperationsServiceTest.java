package com.citycopilot.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommerceOperationsServiceTest {

    @Test
    void rejectsInvalidSummaryRangeBeforeQueryingTheDatabase() {
        CommerceOperationsService service = new CommerceOperationsService(
                mock(JdbcTemplate.class),
                mock(CommerceOrderService.class),
                new ObjectMapper(),
                mock(CommerceRoutingGuard.class),
                mock(CommerceAdmissionDurabilityService.class)
        );
        Instant now = Instant.parse("2026-07-30T00:00:00Z");

        assertThatThrownBy(() -> service.summary(now, now))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("A valid summary time range is required");
    }

    @Test
    void replaysACommerceDeadLetterThroughFreshLuaAdmission() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommerceOrderService orders = mock(CommerceOrderService.class);
        CommerceRoutingGuard routing = mock(CommerceRoutingGuard.class);
        CommerceOperationsService service = new CommerceOperationsService(
                jdbc,
                orders,
                new ObjectMapper(),
                routing,
                mock(CommerceAdmissionDurabilityService.class)
        );
        when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("1720000000000-0")
        )).thenReturn(Map.of(
                "source_id", "1720000000000-0",
                "payload_json", """
                        {"orderId":"300","voucherId":"100","userId":"200","idempotencyKey":"request-1"}
                        """
        ));
        when(jdbc.update(
                org.mockito.ArgumentMatchers.contains("SET replayed_at=CURRENT_TIMESTAMP"),
                org.mockito.ArgumentMatchers.eq("1720000000000-0")
        )).thenReturn(1);
        when(orders.replayAdmission(300L, 100L, 200L, "request-1")).thenReturn(Map.of(
                "orderId", "300",
                "state", "processing"
        ));

        Map<String, Object> result = service.replayDeadLetter(
                "1720000000000-0",
                "user:1"
        );

        assertThat(result)
                .containsEntry("status", "replayed")
                .containsEntry("deadLetterRecordId", "1720000000000-0")
                .containsEntry("orderId", "300");
        verify(routing).requireAdmissionActive(100L);
        verify(orders).replayAdmission(300L, 100L, 200L, "request-1");
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO commerce_outbox_event"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void replayIsBlockedUntilRequiredInventoryRepairCompletes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommerceOrderService orders = mock(CommerceOrderService.class);
        CommerceOperationsService service = new CommerceOperationsService(
                jdbc,
                orders,
                new ObjectMapper(),
                mock(CommerceRoutingGuard.class),
                mock(CommerceAdmissionDurabilityService.class)
        );
        when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.contains("LEFT JOIN commerce_order_recovery"),
                org.mockito.ArgumentMatchers.eq("1720000000000-0")
        )).thenReturn(Map.of(
                "source_id", "1720000000000-0",
                "payload_json", """
                        {"orderId":300,"voucherId":100,"userId":200,"idempotencyKey":"request-1"}
                        """,
                "recovery_phase", "REPAIR_REQUIRED"
        ));

        assertThatThrownBy(() -> service.replayDeadLetter("1720000000000-0", "operator"))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("COMMERCE_INVENTORY_REPAIR_REQUIRED")
                );
        verifyNoInteractions(orders);
    }

    @Test
    void operatorRequeuesOneRepairRequiredAdmissionAndWritesAnAuditEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommerceAdmissionDurabilityService admissions = mock(
                CommerceAdmissionDurabilityService.class
        );
        CommerceOperationsService service = new CommerceOperationsService(
                jdbc,
                mock(CommerceOrderService.class),
                new ObjectMapper(),
                mock(CommerceRoutingGuard.class),
                admissions
        );

        Map<String, Object> result = service.requeueAdmissionRepair(300L, "operator:7");

        assertThat(result)
                .containsEntry("status", "requeued")
                .containsEntry("orderId", "300");
        verify(admissions).requeueRepairRequired(300L);
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO commerce_outbox_event"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("commerce.admission.repair.requeued"),
                org.mockito.ArgumentMatchers.eq("commerce_admission_repair_requeue"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.contains("operator:7")
        );
    }
}
