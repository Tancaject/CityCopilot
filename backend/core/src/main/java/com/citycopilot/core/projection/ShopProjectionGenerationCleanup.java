package com.citycopilot.core.projection;

import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.citycopilot.core.service.discovery.ShopSearchProjectionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 由管理入口显式调用一次，不注册后台调度或启动任务。 */
@Service
public class ShopProjectionGenerationCleanup {
    private final ShopProjectionProperties properties;
    private final ShopProjectionLeaseService leases;
    private final ShopSearchProjectionService search;
    private final ShopGeoIndexService geo;

    public ShopProjectionGenerationCleanup(ShopProjectionProperties properties,
            ShopProjectionLeaseService leases, ShopSearchProjectionService search, ShopGeoIndexService geo) {
        this.properties = properties;
        this.leases = leases;
        this.search = search;
        this.geo = geo;
    }

    public List<ProjectionGenerationCleanupResult> cleanup(boolean readersQuiesced) {
        if (!properties.getCleanup().isEnabled()) {
            throw new IllegalStateException("Projection generation cleanup is disabled; configure the approved maintenance window first");
        }
        if (!readersQuiesced) {
            throw new IllegalArgumentException("Drain readers across all replicas and external clients, then explicitly acknowledge readersQuiesced for this request");
        }
        properties.getCleanup().requireMaintenanceWindow();
        if (!properties.getRoles().isReconciliationEnabled() || properties.isLegacyAfterCommitEnabled()
                || !properties.isOutboxEnabled()) {
            throw new IllegalStateException("Projection cleanup requires the reconciliation role and Outbox writer protocol; "
                    + "disable legacy-after-commit writers before enabling cleanup");
        }
        var acquired = leases.tryAcquire(ShopProjectionLeaseService.WRITER_LEASE,
                properties.getMaintenanceLeaseTtl());
        if (acquired.isEmpty()) {
            throw new IllegalStateException("Another Core replica owns the shop projection writer lease; retry the maintenance batch after it finishes");
        }
        try (var lease = acquired.get()) {
            List<ProjectionGenerationCleanupResult> results = new ArrayList<>();
            results.addAll(search.cleanupRetiredGenerations(lease, properties.getCleanup()));
            results.addAll(geo.cleanupRetiredGenerations(lease, properties.getCleanup()));
            return List.copyOf(results);
        }
    }
}
