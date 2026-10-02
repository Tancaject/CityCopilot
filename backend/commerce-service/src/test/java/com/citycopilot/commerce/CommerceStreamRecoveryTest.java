package com.citycopilot.commerce;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class CommerceStreamRecoveryTest {
    @Test
    void streamGroupStartsAtBeginningAndBusyGroupIsTheOnlyIgnoredFailure() {
        Fixture fixture = fixture(true, 10);
        RecordId bootstrap = RecordId.of("1-0");
        when(fixture.stream.add(eq(CommerceRedisKeyspace.STREAM), anyMap())).thenReturn(bootstrap);
        when(fixture.stream.createGroup(
                eq(CommerceRedisKeyspace.STREAM),
                any(ReadOffset.class),
                eq(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP)
        )).thenThrow(new IllegalStateException("BUSYGROUP Consumer Group name already exists"));

        assertThatCode(fixture.service::initializeStream).doesNotThrowAnyException();

        ArgumentCaptor<ReadOffset> offset = ArgumentCaptor.forClass(ReadOffset.class);
        verify(fixture.stream).createGroup(
                eq(CommerceRedisKeyspace.STREAM),
                offset.capture(),
                eq(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP)
        );
        assertThat(offset.getValue().getOffset()).isEqualTo("0-0");
        verify(fixture.stream).delete(CommerceRedisKeyspace.STREAM, bootstrap);
    }

    @Test
    void nonBusyGroupInitializationFailureStopsStartup() {
        Fixture fixture = fixture(true, 10);
        RecordId bootstrap = RecordId.of("1-0");
        when(fixture.stream.add(eq(CommerceRedisKeyspace.STREAM), anyMap())).thenReturn(bootstrap);
        when(fixture.stream.createGroup(
                eq(CommerceRedisKeyspace.STREAM),
                any(ReadOffset.class),
                eq(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP)
        )).thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(fixture.service::initializeStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection refused");
        verify(fixture.stream).delete(CommerceRedisKeyspace.STREAM, bootstrap);
    }

    @Test
    void pendingRecoveryScansEveryPageWithAnExclusiveCursor() {
        Fixture fixture = fixture(true, 2);
        Consumer deadConsumer = Consumer.from(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP, "dead-consumer");
        PendingMessages first = new PendingMessages(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP, List.of(
                new PendingMessage(RecordId.of("1-0"), deadConsumer, Duration.ofMinutes(1), 1),
                new PendingMessage(RecordId.of("2-0"), deadConsumer, Duration.ofMinutes(1), 1)
        ));
        PendingMessages second = new PendingMessages(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP, List.of(
                new PendingMessage(RecordId.of("3-0"), deadConsumer, Duration.ofMinutes(1), 1)
        ));
        when(fixture.stream.pending(
                eq(CommerceRedisKeyspace.STREAM),
                eq(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP),
                any(Range.class),
                eq(2L)
        )).thenReturn(first, second);
        when(fixture.stream.claim(
                eq(CommerceRedisKeyspace.STREAM),
                eq(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP),
                anyString(),
                eq(Duration.ofSeconds(30)),
                any(RecordId[].class)
        )).thenReturn(List.of());

        fixture.service.reclaimPendingOrders();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Range> ranges = ArgumentCaptor.forClass(Range.class);
        verify(fixture.stream, times(2)).pending(
                eq(CommerceRedisKeyspace.STREAM),
                eq(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP),
                ranges.capture(),
                eq(2L)
        );
        assertThat(ranges.getAllValues().get(0).getLowerBound().isBounded()).isFalse();
        Range<?> secondRange = ranges.getAllValues().get(1);
        assertThat(secondRange.getLowerBound().getValue().map(String::valueOf)).contains("2-0");
        assertThat(secondRange.getLowerBound().isInclusive()).isFalse();
        verify(fixture.stream, times(2)).claim(
                eq(CommerceRedisKeyspace.STREAM),
                eq(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP),
                anyString(),
                eq(Duration.ofSeconds(30)),
                any(RecordId[].class)
        );
    }

    @Test
    void disabledStreamWorkerDoesNotRunAnyScheduledStreamOrRecoveryWork() {
        Fixture fixture = fixture(false, 10);

        fixture.service.initializeStream();
        fixture.service.consumeOrders();
        fixture.service.reclaimPendingOrders();
        fixture.service.resumePendingInventoryRestores();

        verifyNoInteractions(fixture.redis, fixture.jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poisonRecordIsDurablyDeadLetteredBeforeItIsAcknowledged() {
        Fixture fixture = fixture(true, 10);
        MapRecord<String, Object, Object> poison = mock(MapRecord.class);
        RecordId recordId = RecordId.of("1720000000000-0");
        when(poison.getId()).thenReturn(recordId);
        when(poison.getStream()).thenReturn(CommerceRedisKeyspace.STREAM);
        when(poison.getValue()).thenReturn(Map.of("voucherId", "100"));
        when(fixture.jdbc.update(
                contains("INSERT INTO commerce_dead_letter"),
                any(Object[].class)
        )).thenReturn(1);
        when(fixture.redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(1L);

        fixture.service.processSafely(poison, 10L);

        verify(fixture.jdbc).update(
                contains("INSERT INTO commerce_dead_letter"),
                eq("0:1:0:1720000000000-0"),
                anyString(),
                contains("operator_repair_required")
        );
        verify(fixture.redis).execute(
                any(DefaultRedisScript.class),
                eq(List.of(CommerceRedisKeyspace.STREAM)),
                any(Object[].class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseablePoisonAdmissionIsCompensatedBeforeDeadLetterAck() {
        Fixture fixture = fixture(true, 10);
        MapRecord<String, Object, Object> poison = mock(MapRecord.class);
        RecordId recordId = RecordId.of("1720000000001-0");
        when(poison.getId()).thenReturn(recordId);
        when(poison.getStream()).thenReturn(CommerceRedisKeyspace.STREAM);
        when(poison.getValue()).thenReturn(Map.of(
                "orderId", "300",
                "voucherId", "100",
                "userId", "200",
                "idempotencyKey", "request-1"
        ));
        when(fixture.jdbc.queryForObject(
                contains("COUNT(*) FROM commerce_order_recovery"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(null, 1);
        when(fixture.jdbc.queryForObject(
                argThat(sql -> sql.contains("FROM commerce_order")
                        && !sql.contains("commerce_order_recovery")),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);
        when(fixture.jdbc.queryForObject(
                contains("FROM commerce_reservation"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);
        when(fixture.jdbc.queryForObject(
                contains("SELECT phase FROM commerce_order_recovery"),
                eq(String.class),
                any(Object[].class)
        )).thenReturn("RECEIVED");
        when(fixture.jdbc.queryForObject(
                contains("existing_purchase"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);
        when(fixture.jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(fixture.redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(1L);

        fixture.service.processSafely(poison, 10L);

        verify(fixture.redis, times(2)).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        );
        verify(fixture.jdbc).update(
                contains("INSERT INTO commerce_dead_letter"),
                eq("0:1:0:1720000000001-0"),
                anyString(),
                contains("admission_compensated")
        );
        verify(fixture.stream, never()).acknowledge(
                eq(CommerceRedisKeyspace.STREAM),
                eq(CommerceRedisKeyspace.ORDER_CONSUMER_GROUP),
                any(RecordId.class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureAfterDurableCompensationIntentLeavesRecordPendingForReplay() {
        Fixture fixture = fixture(true, 10);
        MapRecord<String, Object, Object> poison = mock(MapRecord.class);
        RecordId recordId = RecordId.of("1720000000002-0");
        when(poison.getId()).thenReturn(recordId);
        when(poison.getStream()).thenReturn(CommerceRedisKeyspace.STREAM);
        when(poison.getValue()).thenReturn(Map.of(
                "orderId", "301",
                "voucherId", "100",
                "userId", "201",
                "idempotencyKey", "request-2"
        ));
        when(fixture.jdbc.queryForObject(
                contains("COUNT(*) FROM commerce_order_recovery"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(null, 1);
        when(fixture.jdbc.queryForObject(
                argThat(sql -> sql.contains("FROM commerce_order")
                        && !sql.contains("commerce_order_recovery")),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);
        when(fixture.jdbc.queryForObject(
                contains("FROM commerce_reservation"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);
        when(fixture.jdbc.queryForObject(
                contains("SELECT phase FROM commerce_order_recovery"),
                eq(String.class),
                any(Object[].class)
        )).thenReturn("RECEIVED");
        when(fixture.jdbc.queryForObject(
                contains("existing_purchase"),
                eq(Integer.class),
                any(Object[].class)
        )).thenReturn(0);
        when(fixture.jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(fixture.redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenThrow(new IllegalStateException("redis unavailable"));

        fixture.service.processSafely(poison, 10L);

        verify(fixture.jdbc).update(
                contains("SET phase=?, last_error=?"),
                eq("COMPENSATION_REQUIRED"),
                anyString(),
                eq("0:1:0:1720000000002-0"),
                eq("RECEIVED")
        );
        verify(fixture.jdbc, never()).update(
                contains("INSERT INTO commerce_dead_letter"),
                any(Object[].class)
        );
        verify(fixture.redis).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        );
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(boolean enabled, int batchSize) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
                    invocation.getArgument(0);
            callback.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(redis.opsForStream()).thenReturn(stream);
        var json = JsonMapper.builderWithJackson2Defaults().build();
        var keyspace = new CommerceRedisKeyspace(1, "0");
        var durable = mock(CommerceAdmissionDurabilityService.class);
        var recovery = new CommerceOrderRecoveryStore(jdbc);
        var orders = new CommerceOrderService(jdbc, redis, transactions, json, durable,
                keyspace, null, null, recovery);
        CommerceOrderStreamWorker service = new CommerceOrderStreamWorker(jdbc, redis, transactions, json,
                durable, keyspace, null, orders, mock(CommerceOrderAdmissionService.class), recovery,
                "test", enabled, batchSize, 10);
        return new Fixture(jdbc, redis, stream, service);
    }

    private record Fixture(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            StreamOperations<String, Object, Object> stream,
            CommerceOrderStreamWorker service
    ) {
    }
}
