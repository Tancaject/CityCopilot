package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.AdmissionRepairRequeued;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommerceOperationsServiceTest {

    @Test
    void rejectsInvalidSummaryRangeBeforeQueryingTheDatabase() {
        CommerceOperationsService service = new CommerceOperationsService(
                mock(JdbcTemplate.class),
                mock(CommerceOrderAdmissionService.class),
                JsonMapper.builderWithJackson2Defaults().build(),
                mock(CommerceAdmissionDurabilityService.class),
                inlineTransactions()
        );
        Instant now = Instant.parse("2026-07-30T00:00:00Z");

        assertThatThrownBy(() -> service.summary(now, now))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("A valid summary time range is required");
    }

    @Test
    void replaysACommerceDeadLetterThroughFreshLuaAdmission() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommerceOrderAdmissionService orders = mock(CommerceOrderAdmissionService.class);
        CommerceOperationsService service = new CommerceOperationsService(
                jdbc,
                orders,
                JsonMapper.builderWithJackson2Defaults().build(),
                mock(CommerceAdmissionDurabilityService.class),
                inlineTransactions()
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

        DeadLetterReplayResult result = service.replayDeadLetter(
                "1720000000000-0",
                "user:1"
        );

        assertThat(result.status()).isEqualTo("replayed");
        assertThat(result.deadLetterRecordId()).isEqualTo("1720000000000-0");
        assertThat(result.orderId()).isEqualTo("300");
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
        CommerceOrderAdmissionService orders = mock(CommerceOrderAdmissionService.class);
        CommerceOperationsService service = new CommerceOperationsService(
                jdbc,
                orders,
                JsonMapper.builderWithJackson2Defaults().build(),
                mock(CommerceAdmissionDurabilityService.class),
                inlineTransactions()
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
                mock(CommerceOrderAdmissionService.class),
                JsonMapper.builderWithJackson2Defaults().build(),
                admissions,
                inlineTransactions()
        );

        AdmissionRepairRequeued result = service.requeueAdmissionRepair(300L, "operator:7");

        assertThat(result).isEqualTo(new AdmissionRepairRequeued("requeued", "300"));
        verify(admissions).requeueRepairRequired(300L);
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO commerce_outbox_event"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("commerce.admission.repair.requeued"),
                org.mockito.ArgumentMatchers.eq("commerce_admission_repair_requeue"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.contains("operator:7")
        );
    }

    @Test
    void replayAdmissionCommitsOutsideTheMarkerTransactionAndAuditFailureStaysReplayable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommerceOrderAdmissionService orders = mock(CommerceOrderAdmissionService.class);
        InlineTransactions transactions = inlineTransactions();
        CommerceOperationsService service = new CommerceOperationsService(
                jdbc,
                orders,
                JsonMapper.builderWithJackson2Defaults().build(),
                mock(CommerceAdmissionDurabilityService.class),
                transactions
        );
        when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.contains("LEFT JOIN commerce_order_recovery"),
                org.mockito.ArgumentMatchers.eq("1720000000000-0")
        )).thenReturn(Map.of(
                "source_id", "1720000000000-0",
                "payload_json", """
                        {"orderId":300,"voucherId":100,"userId":200,"idempotencyKey":"request-1"}
                        """
        ));
        when(orders.replayAdmission(300L, 100L, 200L, "request-1")).thenAnswer(invocation -> {
            // Redis 准入无法回滚，账本必须独立提交，不能被重放标记的事务包住。
            assertThat(transactions.active).isFalse();
            return Map.of("orderId", "300", "state", "processing", "code", "IDEMPOTENT_REPLAY");
        });
        when(jdbc.update(
                org.mockito.ArgumentMatchers.contains("SET replayed_at=CURRENT_TIMESTAMP"),
                org.mockito.ArgumentMatchers.eq("1720000000000-0")
        )).thenReturn(1);
        when(jdbc.update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO commerce_outbox_event"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenThrow(new DataAccessResourceFailureException("outbox unavailable")).thenReturn(1);

        assertThatThrownBy(() -> service.replayDeadLetter("1720000000000-0", "operator"))
                .hasMessage("Unable to persist dead-letter replay audit");
        DeadLetterReplayResult retried = service.replayDeadLetter("1720000000000-0", "operator");

        assertThat(retried.status()).isEqualTo("replayed");
        assertThat(retried.orderId()).isEqualTo("300");
        verify(orders, times(2)).replayAdmission(300L, 100L, 200L, "request-1");
    }

    /** 同步执行回调并记录是否处于事务内，替代真实事务管理器。 */
    static InlineTransactions inlineTransactions() {
        return new InlineTransactions();
    }

    static final class InlineTransactions extends TransactionTemplate {
        boolean active;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            active = true;
            try {
                return action.doInTransaction(mock(TransactionStatus.class));
            } finally {
                active = false;
            }
        }
    }
}
