package com.citycopilot.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.geo",
        name = "reconcile-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ShopGeoIndexReconciliationJob {

    private final ShopGeoIndexService shopGeoIndexService;

    public ShopGeoIndexReconciliationJob(ShopGeoIndexService shopGeoIndexService) {
        this.shopGeoIndexService = shopGeoIndexService;
    }

    @Scheduled(
            initialDelayString = "${citycopilot.geo.reconcile-interval-ms:60000}",
            fixedDelayString = "${citycopilot.geo.reconcile-interval-ms:60000}"
    )
    public void reconcileIfNecessary() {
        if (shopGeoIndexService.isReady()) {
            return;
        }
        try {
            int indexed = shopGeoIndexService.syncShopGeoIndex();
            log.info("Reconciled Redis GEO shop index: indexed={}", indexed);
        } catch (RuntimeException error) {
            log.warn("Redis GEO index reconciliation failed; SQL fallback remains active: {}",
                    error.getMessage());
        }
    }
}
