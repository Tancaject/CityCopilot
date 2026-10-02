package com.citycopilot.commerce;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommerceOrderProcessingTest {
    private static final String RECORD_ID = "1720000000000-0";
    private static final String SOURCE_ID = "0:1:0:" + RECORD_ID;

    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private TransactionTemplate transactions;
    private CommerceOrderIdGenerator ids;
    private CommerceAdmissionDurabilityService durableAdmissions;
    private CommerceRedisReplicationExecutor replicatedRedis;
    private CommerceOrderService service;
    private CommerceOrderAdmissionService admissions;
    private CommerceOrderStreamWorker worker;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        transactions = mock(TransactionTemplate.class);
        ids = mock(CommerceOrderIdGenerator.class);
        durableAdmissions = mock(CommerceAdmissionDurabilityService.class);
        replicatedRedis = mock(CommerceRedisReplicationExecutor.class);
        var json = JsonMapper.builderWithJackson2Defaults().build();
        var keyspace = new CommerceRedisKeyspace(1, "0");
        var recovery = new CommerceOrderRecoveryStore(jdbc);
        service = new CommerceOrderService(jdbc, redis, transactions, json, durableAdmissions,
                keyspace, null, null, recovery);
        admissions = new CommerceOrderAdmissionService(jdbc, ids, durableAdmissions, replicatedRedis,
                keyspace, null, null);
        worker = new CommerceOrderStreamWorker(jdbc, redis, transactions, json, durableAdmissions,
                keyspace, null, service, admissions, recovery, "test", true, 10, 10);
    }

    @Test
    void orderReservationOutboxAndAdmissionStayInsideOneTransactionCallback() {
        AtomicBoolean inTransaction = new AtomicBoolean();
        List<String> writes = new ArrayList<>();
        doAnswer(invocation -> {
            assertThat(inTransaction.compareAndSet(false, true)).isTrue();
            try {
                Consumer<TransactionStatus> callback = invocation.getArgument(0);
                callback.accept(mock(TransactionStatus.class));
            } finally {
                inTransaction.set(false);
            }
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            assertThat(inTransaction).isTrue();
            writes.add(invocation.getArgument(0));
            return 1;
        });
        when(jdbc.queryForMap(contains("FROM commerce_voucher"), eq(100L))).thenReturn(Map.of(
                "shop_id", 1L, "title", "券", "shop_name", "店", "pay_value", 100, "actual_value", 200));
        doAnswer(invocation -> {
            assertThat(inTransaction).isTrue();
            writes.add("DURABLE_ORDER_CREATED");
            return null;
        }).when(durableAdmissions).markOrderCreated(300L, 100L, 200L, "request-1");

        service.createOrder(SOURCE_ID, "claim-1", 300L, 100L, 200L, "request-1");

        assertThat(writes).hasSize(8);
        assertThat(writes.get(0)).contains("SET claimed_until=");
        assertThat(writes.get(1)).contains("INSERT INTO commerce_reservation");
        assertThat(writes.get(2)).contains("stock=stock-1");
        assertThat(writes.get(3)).contains("INSERT INTO commerce_order(");
        assertThat(writes.get(4)).contains("UPDATE commerce_reservation");
        assertThat(writes.get(5)).contains("INSERT INTO commerce_outbox_event");
        assertThat(writes.get(6)).isEqualTo("DURABLE_ORDER_CREATED");
        assertThat(writes.get(7)).contains("terminal_at=CURRENT_TIMESTAMP");
        verify(transactions).executeWithoutResult(any());
    }

    @Test
    void admissionWithAPreallocatedIdAvoidsJdbcAndUsesOneSameSlotVoucherScript() {
        when(durableAdmissions.prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        )).thenReturn(preparedAdmission());
        when(replicatedRedis.reserveGlobalIdentity(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(replicatedRedis.executeReplicatedScript(
                anyString(), anyString(), any(DefaultRedisScript.class),
                anyList(), any(String[].class)
        )).thenReturn(0L);

        var result = admissions.seckill(100L, 200L, "request-1");

        assertThat(result.orderId()).isEqualTo("300");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(replicatedRedis).executeReplicatedScript(
                eq("voucher_admission"),
                anyString(),
                any(DefaultRedisScript.class),
                keys.capture(),
                any(String[].class)
        );
        assertThat(keys.getValue()).hasSize(8);
        assertThat(keys.getValue())
                .allSatisfy(key -> assertThat(String.valueOf(key))
                        .contains("{commerce:0:1:0}"));
        verify(durableAdmissions).markAccepted(preparedAdmission());
        verifyNoInteractions(jdbc, transactions);
    }

    @Test
    void shardRoutingGateClosesAdmissionWhenCutoverStarts() {
        when(durableAdmissions.prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        )).thenReturn(preparedAdmission());
        when(replicatedRedis.reserveGlobalIdentity(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(replicatedRedis.executeReplicatedScript(
                anyString(), anyString(), any(DefaultRedisScript.class),
                anyList(), any(String[].class)
        )).thenReturn(11L);

        assertThatThrownBy(() -> admissions.seckill(100L, 200L, "request-1"))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_ROUTING_NOT_ACTIVE")
                );
    }

    @Test
    void replicatedAdmissionFailureReportsUnknownOutcomeAndRequiresSameKeyRetry() {
        when(durableAdmissions.prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        )).thenReturn(preparedAdmission());
        when(replicatedRedis.reserveGlobalIdentity(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(replicatedRedis.executeReplicatedScript(
                anyString(), anyString(), any(DefaultRedisScript.class),
                anyList(), any(String[].class)
        )).thenThrow(new IllegalStateException("WAIT confirmation timed out"));

        assertThatThrownBy(() -> admissions.seckill(100L, 200L, "request-1"))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(
                                    "COMMERCE_ADMISSION_OUTCOME_UNKNOWN"
                            );
                            assertThat(error.getMessage()).contains("same Idempotency-Key");
                        }
                );
        verify(durableAdmissions, never()).markAccepted(any());
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

        worker.process(record);
        worker.process(record);

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

        worker.process(record);

        verify(transactions, never()).executeWithoutResult(any());
        verify(redis, never()).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        );
    }

    @Test
    void idempotencyKeyCannotReplayAnOrderForAnotherVoucher() {
        when(durableAdmissions.prepare(
                100L, 200L, "request-1", "seckill:v1:100", null
        )).thenReturn(preparedAdmission());
        CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(1, "0");
        when(replicatedRedis.reserveGlobalIdentity(
                eq(keyspace.globalIdempotency(200L, "request-1")),
                anyString()
        ))
                .thenReturn("300|99|seckill:v1:99");

        assertThatThrownBy(() -> admissions.seckill(100L, 200L, "request-1"))
                .isInstanceOfSatisfying(
                        CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_IDEMPOTENCY_CONFLICT")
                );
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
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

        worker.completeRecovery(SOURCE_ID, "claim-1", 300L, 100L, 200L, "request-1");

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

        worker.completeRecovery(SOURCE_ID, "claim-1", 300L, 100L, 200L, "request-1");

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
        when(record.getId()).thenReturn(RecordId.of(RECORD_ID));
        when(record.getValue()).thenReturn(Map.of(
                "orderId", "300",
                "voucherId", "100",
                "userId", "200",
                "idempotencyKey", "request-1"
        ));
        return record;
    }

    private CommerceAdmissionDurabilityService.Admission preparedAdmission() {
        return new CommerceAdmissionDurabilityService.Admission(
                300L,
                100L,
                200L,
                "request-1",
                "seckill:v1:100",
                CommerceAdmissionDurabilityService.Status.PREPARED,
                null,
                0,
                null,
                0,
                null,
                "0",
                1
        );
    }
}
