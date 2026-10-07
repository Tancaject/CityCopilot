package com.citycopilot.core.projection;

import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.service.ShopGeoIndexService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 由管理入口显式调用一次，不注册后台调度或启动任务。 */
@Service
public class ShopProjectionGenerationCleanup {
    private final ShopProjectionProperties properties;
    private final ShopProjectionLeaseService leases;
    private final ShopGeoIndexService geo;

    public ShopProjectionGenerationCleanup(ShopProjectionProperties properties,
            ShopProjectionLeaseService leases, ShopGeoIndexService geo) {
        this.properties = properties;
        this.leases = leases;
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
        if (!properties.getRoles().isReconciliationEnabled()) {
            throw new IllegalStateException("Projection cleanup requires the reconciliation role");
        }
        var acquired = leases.tryAcquire(ShopProjectionLeaseService.WRITER_LEASE,
                properties.getMaintenanceLeaseTtl());
        if (acquired.isEmpty()) {
            throw new IllegalStateException("Another Core replica owns the shop projection writer lease; retry the maintenance batch after it finishes");
        }
        try (var lease = acquired.get()) {
            return List.copyOf(geo.cleanupRetiredGenerations(lease, properties.getCleanup()));
        }
    }
}
