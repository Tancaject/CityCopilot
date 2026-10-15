package com.citycopilot.commerce;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

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

class CommerceOutboxRelayTest {
    private JdbcTemplate jdbc;
    private CommerceOutboxRelay relay;
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        kafka = mock(KafkaTemplate.class);
        relay = new CommerceOutboxRelay(jdbc, new SimpleMeterRegistry(), kafka, true);
    }

    @Test
    void archivedMessageKeepsOriginalBytesAndRejectsMissingOrChangedHistory() throws Exception {
        String payload = "{\"eventId\":\"event-1\", \"detail\":\"原始正文\\n保留格式\"}";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> row = new LinkedHashMap<>(Map.of(
                "topic", "citycopilot-commerce-events-v1", "event_type", "commerce.booking.confirmed",
                "aggregate_id", "booking-9", "payload_json", payload, "payload_archived_at", java.sql.Timestamp.from(java.time.Instant.now()),
                "archived_payload_hash", hash));
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(row);
        var claim = new CommerceOutboxRelay.OutboxClaim("event-1", "claim-1", 7L);
        var record = relay.claimedRecord(claim);
        assertThat(record.value()).isEqualTo(payload);
        // 聚合 ID 作 key，同一聚合的事件落在同一分区；eventId 和原文哈希放消息头供下游去重。
        assertThat(record.key()).isEqualTo("booking-9");
        assertThat(header(record, "eventId")).isEqualTo("event-1");
        assertThat(header(record, "payloadHash")).isEqualTo(hash);
        verify(jdbc).queryForMap(contains("e.claim_token=? AND e.fencing_token=? AND e.status='CLAIMED'"),
                eq("event-1"), eq("claim-1"), eq(7L));
        row.put("payload_json", payload + " ");
        assertThatThrownBy(() -> relay.claimedRecord(claim)).hasMessageContaining("archive hash mismatch");
        row.put("payload_json", null);
        assertThatThrownBy(() -> relay.claimedRecord(claim)).hasMessageContaining("payload missing");
        row.put("payload_json", payload);
        row.put("payload_archived_at", null);
        row.put("archived_payload_hash", null);
        assertThat(relay.claimedRecord(claim).value()).isEqualTo(payload);
    }

    @Test
    void relayCandidatesIncludeExpiredClaimedRows() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        relay.relayCandidates();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(String.class));
        assertThat(sql.getValue())
                .contains("status IN ('READY','FAILED')")
                .contains("status='CLAIMED'")
                .contains("claimed_until < CURRENT_TIMESTAMP(3)")
                .contains("next_attempt_at <= CURRENT_TIMESTAMP(3)");
    }

    @Test
    void claimCanTakeReadyFailedOrExpiredClaimedRowsAndReturnsFence() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(
                contains("SELECT fencing_token"),
                eq(Long.class),
                eq("event-1"),
                anyString()
        )).thenReturn(7L);

        CommerceOutboxRelay.OutboxClaim claim = relay.claim("event-1");

        assertThat(claim).isNotNull();
        assertThat(claim.eventId()).isEqualTo("event-1");
        assertThat(claim.fencingToken()).isEqualTo(7L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("fencing_token=COALESCE(fencing_token,0)+1")
                .contains("status='CLAIMED'")
                .contains("claimed_until < CURRENT_TIMESTAMP(3)");
    }

    @Test
    void failedPublishRemainsRetryableWithCappedExponentialBackoff() {
        CommerceOutboxRelay.OutboxClaim claim =
                new CommerceOutboxRelay.OutboxClaim("event-1", "claim-1", 8L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(relay.markFailed(claim, new RuntimeException("broker unavailable"))).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
                sql.capture(),
                eq("broker unavailable"),
                eq("event-1"),
                eq("claim-1"),
                eq(8L)
        );
        assertThat(sql.getValue())
                .contains("SET status='FAILED'")
                .contains("LEAST(900")
                .doesNotContain("THEN 'DEAD'");
    }

    @Test
    void unacknowledgedSendIsRecordedAsRetryableFailureInsteadOfPublished() {
        when(jdbc.update(contains("SET status='CLAIMED'"), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT fencing_token"), eq(Long.class), eq("event-1"), anyString()))
                .thenReturn(3L);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(new LinkedHashMap<>(Map.of(
                "topic", "citycopilot-commerce-events-v1", "event_type", "commerce.order.created",
                "aggregate_id", "42", "payload_json", "{}")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new KafkaProducerException(null, "Failed to send", new NotEnoughReplicasException("only one in-sync replica"))));
        when(jdbc.update(contains("SET status='FAILED'"), any(Object[].class))).thenReturn(1);

        relay.publish("event-1");

        // 记录最内层原因，运维能直接看到是同步副本不足。
        verify(jdbc).update(contains("SET status='FAILED'"), eq("only one in-sync replica"), eq("event-1"), anyString(), eq(3L));
        verify(jdbc, never()).update(contains("SET status='PUBLISHED'"), any(Object[].class));
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void terminalUpdatesRequireBothClaimTokenAndFenceAndCheckOwnership() {
        CommerceOutboxRelay.OutboxClaim claim =
                new CommerceOutboxRelay.OutboxClaim("event-1", "claim-1", 8L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThat(relay.markPublished(claim)).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("event-1"), eq("claim-1"), eq(8L));
        assertThat(sql.getValue())
                .contains("claim_token=?")
                .contains("fencing_token=?")
                .contains("status='CLAIMED'");
    }
}
