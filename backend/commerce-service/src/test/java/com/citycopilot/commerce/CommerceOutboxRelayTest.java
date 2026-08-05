package com.citycopilot.commerce;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    void relayCandidatesIncludeExpiredClaimedRows() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        relay.relayCandidates();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(String.class));
        assertThat(sql.getValue())
                .contains("'DEAD'")
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
