package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Separates API writes, durable projection consumption and reconciliation so
 * production deployments can assign those roles to different Core replicas.
 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.projection")
public class ShopProjectionProperties {

    /** Append ShopChanged in the business transaction instead of dual writing. */
    private boolean outboxEnabled = true;

    /** Compatibility escape hatch only; production must leave this disabled. */
    private boolean legacyAfterCommitEnabled = false;

    private Roles roles = new Roles();
    private int projectorBatchSize = 50;
    private long projectorPollMs = 250L;
    private Duration claimTtl = Duration.ofSeconds(30);
    private Duration projectorLeaseTtl = Duration.ofSeconds(30);
    private Duration maintenanceLeaseTtl = Duration.ofMinutes(10);
    /**
     * Upper bound for exponential retry backoff. Projection infrastructure
     * failures remain retryable indefinitely; operators do not need to replay
     * a terminal dead-letter row after a long outage.
     */
    private Duration maxRetryDelay = Duration.ofMinutes(15);

    @Data
    public static class Roles {
        private boolean apiEnabled = true;
        private boolean projectorEnabled = true;
        private boolean reconciliationEnabled = true;
    }
}
