package com.citycopilot.core.service;

import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.service.discovery.ShopSearchProjectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.search",
        name = "enabled",
        havingValue = "true"
)
public class ShopSearchProjectionReconciliationJob {

    private final ShopSearchProjectionService projectionService;

    @Autowired(required = false)
    private ShopProjectionProperties projectionProperties;

    public ShopSearchProjectionReconciliationJob(ShopSearchProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @Scheduled(
            initialDelayString = "${citycopilot.search.reconcile-interval-ms:60000}",
            fixedDelayString = "${citycopilot.search.reconcile-interval-ms:60000}"
    )
    public void reconcileIfNecessary() {
        if (projectionProperties != null
                && !projectionProperties.getRoles().isReconciliationEnabled()) {
            return;
        }
        if (!projectionService.needsReconciliation()) {
            return;
        }
        try {
            int indexed = projectionService.rebuildAll();
            log.info("Reconciled Elasticsearch shop projection: indexed={}", indexed);
        } catch (RuntimeException error) {
            log.warn("Elasticsearch shop projection reconciliation failed; legacy discovery remains active: {}",
                    error.getMessage());
        }
    }
}
