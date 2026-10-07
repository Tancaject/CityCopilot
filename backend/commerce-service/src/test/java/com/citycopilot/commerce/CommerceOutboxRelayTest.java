package com.citycopilot.commerce;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceOutboxRelayTest {
    private JdbcTemplate jdbc;
    private CommerceOutboxRelay relay;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        relay = new CommerceOutboxRelay(
                jdbc,
                new SimpleMeterRegistry(),
                false,
                "127.0.0.1:18081",
                false,
                "",
                ""
        );
    }

    @Test
    void archivedMessageKeepsOriginalBytesAndRejectsMissingOrChangedHistory() throws Exception {
        String payload = "{\"eventId\":\"event-1\", \"detail\":\"原始正文\\n保留格式\"}";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> row = new LinkedHashMap<>(Map.of(
                "topic", "citycopilot-commerce-events-v1", "event_type", "commerce.booking.confirmed",
                "payload_json", payload, "payload_archived_at", java.sql.Timestamp.from(java.time.Instant.now()),
                "archived_payload_hash", hash));
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(row);
        var claim = new CommerceOutboxRelay.OutboxClaim("event-1", "claim-1", 7L);
        var message = relay.claimedMessage(claim);
        assertThat(StandardCharsets.UTF_8.decode(message.getBody()).toString()).isEqualTo(payload);
        assertThat(message.getKeys()).containsExactly("event-1");
        assertThat(message.getProperties()).containsEntry("payloadHash", hash);
        verify(jdbc).queryForMap(contains("e.claim_token=? AND e.fencing_token=? AND e.status='CLAIMED'"),
                eq("event-1"), eq("claim-1"), eq(7L));
        row.put("payload_json", payload + " ");
        assertThatThrownBy(() -> relay.claimedMessage(claim)).hasMessageContaining("archive hash mismatch");
        row.put("payload_json", null);
        assertThatThrownBy(() -> relay.claimedMessage(claim)).hasMessageContaining("payload missing");
        row.put("payload_json", payload);
        row.put("payload_archived_at", null);
        row.put("archived_payload_hash", null);
        assertThat(StandardCharsets.UTF_8.decode(relay.claimedMessage(claim).getBody()).toString()).isEqualTo(payload);
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
    void bookingEventsHaveTheirOwnTagWithoutChangingExistingConsumerTags() {
        assertThat(CommerceOutboxRelay.eventTag("commerce.booking.confirmed")).isEqualTo("BookingEvent");
        assertThat(CommerceOutboxRelay.eventTag("commerce.booking.rescheduled")).isEqualTo("BookingEvent");
        assertThat(CommerceOutboxRelay.eventTag("commerce.order.created")).isEqualTo("CommerceOrderEvent");
        assertThat(CommerceOutboxRelay.eventTag("commerce.stock.discrepancy-detected")).isEqualTo("StockDiscrepancyDetected");
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
