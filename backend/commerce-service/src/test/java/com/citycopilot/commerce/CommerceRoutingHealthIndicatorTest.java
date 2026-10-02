package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommerceRoutingHealthIndicatorTest {

    @Test
    void drainingApiIsRemovedFromReadinessWhileWorkersRemainAvailable() {
        CommerceRoutingGuard routing = mock(CommerceRoutingGuard.class);
        CommerceRoutingGuard.RoutingSnapshot snapshot = new CommerceRoutingGuard.RoutingSnapshot(
                CommerceRoutingGuard.RoutingState.DRAINING,
                "g1",
                4,
                "revision01"
        );
        when(routing.current()).thenReturn(snapshot);
        when(routing.isLocal(snapshot)).thenReturn(true);
        when(routing.localShardGatesMatch(CommerceRoutingGuard.RoutingState.DRAINING))
                .thenReturn(true);

        assertThat(new CommerceRoutingHealthIndicator(routing, true).health().getStatus())
                .isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(new CommerceRoutingHealthIndicator(routing, false).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void aReplicaWithAnotherFingerprintIsOutOfService() {
        CommerceRoutingGuard routing = mock(CommerceRoutingGuard.class);
        CommerceRoutingGuard.RoutingSnapshot snapshot = new CommerceRoutingGuard.RoutingSnapshot(
                CommerceRoutingGuard.RoutingState.ACTIVE,
                "g2",
                8,
                "revision02"
        );
        when(routing.current()).thenReturn(snapshot);
        when(routing.isLocal(snapshot)).thenReturn(false);

        assertThat(new CommerceRoutingHealthIndicator(routing, true).health().getStatus())
                .isEqualTo(Status.OUT_OF_SERVICE);
    }
}
