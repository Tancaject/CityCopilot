package com.citycopilot.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceOrderProcessingTest {
    private static final String SOURCE_ID = "1720000000000-0";

    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private TransactionTemplate transactions;
    private CommerceOrderService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        transactions = mock(TransactionTemplate.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        service = new CommerceOrderService(
                jdbc,
                redis,
                transactions,
                new ObjectMapper(),
                ids,
                "test",
                true,
                10
        );
    }

    @Test
    void deadLetterAckFailureReplayOnlyRetriesFinalization() {
        MapRecord<String, Object, Object> record = record();
        when(jdbc.queryForObject(
                contains("COUNT(*) FROM commerce_order_recovery"),
                eq(Integer.class),
                eq(SOURCE_ID),
                eq(300L),
                eq(100L),
                eq(200L),
                eq("request-1")
        )).thenReturn(1);
        when(jdbc.queryForObject(
                startsWith("SELECT phase FROM commerce_order_recovery"),
                eq(String.class),
                eq(SOURCE_ID)
        )).thenReturn("DEAD_LETTERED");
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenThrow(new IllegalStateException("ack unavailable")).thenReturn(1L);

        service.process(record);
        service.process(record);

        verify(redis, times(2)).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        );
        verify(transactions, never()).executeWithoutResult(any());
        verify(jdbc, never()).queryForMap(contains("commerce_voucher"), any(Object[].class));
        verify(jdbc, never()).update(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("INSERT INTO commerce_order(")),
                any(Object[].class)
        );
    }

    @Test
    void activeLeaseOwnedByAnotherConsumerPreventsBusinessProcessing() {
        MapRecord<String, Object, Object> record = record();
        when(jdbc.queryForObject(
                contains("COUNT(*) FROM commerce_order_recovery"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(1);
        when(jdbc.queryForObject(
                startsWith("SELECT phase FROM commerce_order_recovery"),
                eq(String.class),
                eq(SOURCE_ID)
        )).thenReturn("RECEIVED");
        when(jdbc.update(
                contains("claim_version=claim_version+1"),
                any(Object[].class)
        )).thenReturn(0);

        service.process(record);

        verify(transactions, never()).executeWithoutResult(any());
        verify(redis, never()).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        );
    }

    @Test
    void idempotencyKeyCannotReplayAnOrderForAnotherVoucher() {
        when(jdbc.queryForList(
                contains("WHERE user_id=? AND idempotency_key=?"),
                eq(200L),
                eq("request-1")
        )).thenReturn(List.of(Map.of(
                "id", 300L,
                "voucher_id", 99L,
                "status", 1,
                "version", 0
        )));

        assertThatThrownBy(() -> service.seckill(100L, 200L, "request-1"))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_IDEMPOTENCY_CONFLICT")
                );
        verify(redis, never()).opsForValue();
    }

    @Test
    void missingRedisStockIsPersistedAsRepairRequiredAndCanTerminate() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(
                contains("SELECT last_error"),
                eq(String.class),
                eq(SOURCE_ID)
        )).thenReturn("database insert failed");
        when(jdbc.queryForObject(
                contains("existing_purchase"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(-2L);

        service.completeRecovery(SOURCE_ID, "claim-1", 300L, 100L, 200L, "request-1");

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("SET phase=?")
                        && sql.contains("compensation_delta=?")
                        && sql.contains("terminal_at=")
                ),
                eq("REPAIR_REQUIRED"),
                eq(1),
                contains("compensation_result=-2"),
                eq(SOURCE_ID),
                eq("claim-1"),
                eq("COMPENSATION_REQUIRED")
        );
    }

    @Test
    void unprovenCompensationResultAlsoRequiresEvidenceBasedRepair() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(
                contains("SELECT last_error"),
                eq(String.class),
                eq(SOURCE_ID)
        )).thenReturn("database insert failed");
        when(jdbc.queryForObject(
                contains("existing_purchase"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(0L);

        service.completeRecovery(SOURCE_ID, "claim-1", 300L, 100L, 200L, "request-1");

        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("SET phase=?")
                                && sql.contains("compensation_delta=?")
                                && sql.contains("terminal_at=")
                ),
                eq("REPAIR_REQUIRED"),
                eq(1),
                contains("compensation_result=0"),
                eq(SOURCE_ID),
                eq("claim-1"),
                eq("COMPENSATION_REQUIRED")
        );
    }

    @Test
    void expiredFenceNeverIncrementsRedisAndRecordsRepairRequired() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(0L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        String result = service.attemptTransitionInventoryRestore(
                "transition:300:cancel:0",
                300L,
                100L,
                "expired-token"
        );

        assertThat(result).isEqualTo("REPAIR_REQUIRED");
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("compensation_delta=1")
                        && sql.contains("terminal_at=")
                ),
                eq("REPAIR_REQUIRED"),
                eq("inventory_fence_expired_before_transition_restore"),
                eq("transition:300:cancel:0"),
                eq("RESTORE_PENDING")
        );
        verify(redis, never()).opsForValue();
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, Object, Object> record() {
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of(SOURCE_ID));
        when(record.getValue()).thenReturn(Map.of(
                "orderId", "300",
                "voucherId", "100",
                "userId", "200",
                "idempotencyKey", "request-1"
        ));
        return record;
    }
}
