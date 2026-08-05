package com.citycopilot.core.projection;

import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.citycopilot.core.service.discovery.ShopSearchProjectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * At-least-once ShopChanged projector. A shared MySQL fencing lease serializes
 * this writer with full ES/GEO rebuilds across all Core replicas.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.projection.roles",
        name = "projector-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ShopProjectionOutboxProjector {

    private final ShopProjectionOutboxRepository outbox;
    private final ShopProjectionLeaseService leases;
    private final ShopMapper shopMapper;
    private final ShopGeoIndexService geoIndex;
    private final ShopSearchProjectionService searchProjection;
    private final ShopProjectionProperties properties;

    public ShopProjectionOutboxProjector(
            ShopProjectionOutboxRepository outbox,
            ShopProjectionLeaseService leases,
            ShopMapper shopMapper,
            ShopGeoIndexService geoIndex,
            ShopSearchProjectionService searchProjection,
            ShopProjectionProperties properties
    ) {
        this.outbox = outbox;
        this.leases = leases;
        this.shopMapper = shopMapper;
        this.geoIndex = geoIndex;
        this.searchProjection = searchProjection;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${citycopilot.projection.projector-poll-ms:250}",
            fixedDelayString = "${citycopilot.projection.projector-poll-ms:250}")
    public void projectReadyEvents() {
        var acquired = leases.tryAcquire(
                ShopProjectionLeaseService.WRITER_LEASE,
                properties.getProjectorLeaseTtl());
        if (acquired.isEmpty()) {
            return;
        }
        try (ShopProjectionLeaseService.Lease lease = acquired.get()) {
            List<ShopProjectionOutboxRepository.ClaimedEvent> events = outbox.claimReady(
                    properties.getProjectorBatchSize(),
                    "projector-" + lease.fencingToken(),
                    Math.max(1L, properties.getClaimTtl().toNanos() / 1_000L));
            for (ShopProjectionOutboxRepository.ClaimedEvent event : events) {
                projectOne(event, lease);
            }
        } catch (RuntimeException error) {
            log.warn("Shop projection poll failed: {}", error.getMessage());
        }
    }

    private void projectOne(
            ShopProjectionOutboxRepository.ClaimedEvent event,
            ShopProjectionLeaseService.Lease lease
    ) {
        try {
            lease.renew();
            Shop current = shopMapper.selectById(event.shopId());
            if (current == null) {
                throw new IllegalStateException("Shop deletion projection is not implemented: " + event.shopId());
            }
            long currentVersion = current.getProjectionVersion() == null
                    ? 0L
                    : current.getProjectionVersion();
            if (currentVersion < event.aggregateVersion()) {
                throw new IllegalStateException(
                        "Shop source version is behind outbox event: " + event.eventId());
            }

            // Both targets are retry-safe: ES uses external_gte and GEO stores
            // the last applied source version in the active generation.
            searchProjection.upsertVersioned(current);
            geoIndex.refreshShopGeoIndexVersioned(current, currentVersion);
            lease.assertHeld();
            outbox.complete(event);
        } catch (ShopProjectionOutboxRepository.ClaimLostException error) {
            throw error;
        } catch (RuntimeException error) {
            outbox.fail(event, error, properties.getMaxRetryDelay());
            log.warn("Shop projection event {} failed: {}", event.eventId(), error.getMessage());
        }
    }
}
