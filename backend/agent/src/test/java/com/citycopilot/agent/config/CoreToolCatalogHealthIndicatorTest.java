package com.citycopilot.agent.config;

import com.citycopilot.agent.gateway.RemoteToolGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreToolCatalogHealthIndicatorTest {

    @Test
    void reportsUpWhenCoreCatalogIsAvailable() {
        RemoteToolGateway gateway = mock(RemoteToolGateway.class);
        when(gateway.catalogStatus()).thenReturn(status(
                1,
                true,
                RemoteToolGateway.RefreshOutcome.SUCCESS,
                null
        ));

        var health = new CoreToolCatalogHealthIndicator(gateway).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("fresh", true);
    }

    @Test
    void reportsDownWhenCoreCatalogIsEmptyOrUnavailable() {
        RemoteToolGateway gateway = mock(RemoteToolGateway.class);
        when(gateway.catalogStatus()).thenReturn(status(
                0,
                false,
                RemoteToolGateway.RefreshOutcome.SUCCESS,
                null
        ));
        assertThat(new CoreToolCatalogHealthIndicator(gateway).health().getStatus())
                .isEqualTo(Status.DOWN);

        when(gateway.tools()).thenThrow(new IllegalStateException("core unavailable"));
        assertThat(new CoreToolCatalogHealthIndicator(gateway).health().getStatus())
                .isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenCachedCatalogIsStaleAfterARefreshFailure() {
        RemoteToolGateway gateway = mock(RemoteToolGateway.class);
        when(gateway.catalogStatus()).thenReturn(status(
                3,
                false,
                RemoteToolGateway.RefreshOutcome.FAILURE,
                "core_unavailable"
        ));

        var health = new CoreToolCatalogHealthIndicator(gateway).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("reason", "CORE_TOOL_CATALOG_STALE")
                .containsEntry("toolCount", 3)
                .containsEntry("lastRefreshOutcome", "FAILURE")
                .containsEntry("lastFailureCode", "core_unavailable");
    }

    @Test
    void remainsReadyForARecentCachedCatalogDuringARefreshFailure() {
        RemoteToolGateway gateway = mock(RemoteToolGateway.class);
        when(gateway.catalogStatus()).thenReturn(status(
                3,
                true,
                RemoteToolGateway.RefreshOutcome.FAILURE,
                "core_unavailable"
        ));

        assertThat(new CoreToolCatalogHealthIndicator(gateway).health().getStatus())
                .isEqualTo(Status.UP);
    }

    private RemoteToolGateway.CatalogStatus status(
            int toolCount,
            boolean fresh,
            RemoteToolGateway.RefreshOutcome outcome,
            String failureCode
    ) {
        Instant success = Instant.parse("2026-08-04T00:00:00Z");
        Instant failure = outcome == RemoteToolGateway.RefreshOutcome.FAILURE
                ? Instant.parse("2026-08-04T00:00:10Z")
                : null;
        return new RemoteToolGateway.CatalogStatus(
                toolCount,
                fresh,
                outcome,
                failure == null ? success : failure,
                success,
                failure,
                failureCode,
                Instant.parse("2026-08-04T00:02:00Z"),
                Instant.parse("2026-08-04T00:00:12Z")
        );
    }
}
