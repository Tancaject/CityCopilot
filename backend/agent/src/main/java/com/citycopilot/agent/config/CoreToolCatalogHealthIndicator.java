package com.citycopilot.agent.config;

import com.citycopilot.agent.gateway.RemoteToolGateway;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("coreTools")
@ConditionalOnProperty(
        prefix = "citycopilot.health.core-tools",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CoreToolCatalogHealthIndicator implements HealthIndicator {
    private final RemoteToolGateway toolGateway;

    public CoreToolCatalogHealthIndicator(RemoteToolGateway toolGateway) {
        this.toolGateway = toolGateway;
    }

    @Override
    public Health health() {
        try {
            toolGateway.tools();
            RemoteToolGateway.CatalogStatus status = toolGateway.catalogStatus();
            Health.Builder health;
            if (status.toolCount() == 0) {
                health = Health.down()
                        .withDetail("reason", "CORE_TOOL_CATALOG_EMPTY")
                        .withDetail("fresh", status.fresh());
            } else if (!status.fresh()) {
                health = Health.down()
                        .withDetail("reason", "CORE_TOOL_CATALOG_STALE")
                        .withDetail("fresh", false);
            } else {
                health = Health.up()
                        .withDetail("fresh", true);
            }
            health.withDetail("toolCount", status.toolCount())
                    .withDetail("lastRefreshOutcome", status.lastRefreshOutcome().name());
            addInstant(health, "lastRefreshAttemptAt", status.lastRefreshAttemptAt());
            addInstant(health, "lastSuccessfulRefreshAt", status.lastSuccessfulRefreshAt());
            addInstant(health, "lastFailedRefreshAt", status.lastFailedRefreshAt());
            addInstant(health, "freshUntil", status.freshUntil());
            addInstant(health, "nextRefreshAt", status.nextRefreshAt());
            if (status.lastFailureCode() != null) {
                health.withDetail("lastFailureCode", status.lastFailureCode());
            }
            return health.build();
        } catch (Exception error) {
            return Health.down(error)
                    .withDetail("reason", "CORE_TOOL_CATALOG_UNAVAILABLE")
                    .build();
        }
    }

    private static void addInstant(Health.Builder health, String name, java.time.Instant value) {
        if (value != null) {
            health.withDetail(name, value.toString());
        }
    }
}
