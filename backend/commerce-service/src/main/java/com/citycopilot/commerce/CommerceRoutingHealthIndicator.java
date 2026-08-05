package com.citycopilot.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

@Component
final class CommerceRoutingHealthIndicator implements HealthIndicator {
    private final CommerceRoutingGuard routing;
    private final boolean apiEnabled;

    CommerceRoutingHealthIndicator(
            CommerceRoutingGuard routing,
            @Value("${citycopilot.commerce.api-enabled:true}") boolean apiEnabled
    ) {
        this.routing = routing;
        this.apiEnabled = apiEnabled;
    }

    @Override
    public Health health() {
        try {
            CommerceRoutingGuard.RoutingSnapshot current = routing.current();
            if (current == null) {
                return Health.down()
                        .withDetail("reason", "routing_control_missing")
                        .build();
            }
            Health.Builder health = Health.up()
                    .withDetail("state", current.state())
                    .withDetail("generation", current.generation())
                    .withDetail("physicalShardCount", current.physicalShardCount())
                    .withDetail("revision", current.revision());
            if (!routing.isLocal(current)) {
                return Health.status(Status.OUT_OF_SERVICE)
                        .withDetails(health.build().getDetails())
                        .withDetail("reason", "local_routing_config_mismatch")
                        .build();
            }
            if (!routing.localShardGatesMatch(current.state())) {
                return Health.status(Status.OUT_OF_SERVICE)
                        .withDetails(health.build().getDetails())
                        .withDetail("reason", "shard_routing_gate_mismatch")
                        .build();
            }
            if (apiEnabled && current.state() == CommerceRoutingGuard.RoutingState.DRAINING) {
                return Health.status(Status.OUT_OF_SERVICE)
                        .withDetails(health.build().getDetails())
                        .withDetail("reason", "api_admission_draining")
                        .build();
            }
            return health.build();
        } catch (RuntimeException failure) {
            return Health.down()
                    .withDetail("reason", "routing_control_unavailable")
                    .build();
        }
    }
}
