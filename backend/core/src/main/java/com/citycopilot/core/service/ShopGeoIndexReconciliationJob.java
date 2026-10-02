package com.citycopilot.core.service;

import com.citycopilot.core.config.ShopProjectionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis GEO 店铺索引定时对账任务。
 *
 * <p>仅在索引未就绪且当前实例承担对账角色时全量重建；失败期间查询仍可走 SQL 降级。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.geo",
        name = "reconcile-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ShopGeoIndexReconciliationJob {

    /** GEO 索引服务，提供就绪检查和全量同步。 */
    private final ShopGeoIndexService shopGeoIndexService;

    /** 可选投影角色配置，用于避免非对账实例重复执行。 */
    @Autowired(required = false)
    private ShopProjectionProperties projectionProperties;

    /** @param shopGeoIndexService GEO 索引服务 */
    public ShopGeoIndexReconciliationJob(ShopGeoIndexService shopGeoIndexService) {
        this.shopGeoIndexService = shopGeoIndexService;
    }

    /**
     * 按配置周期检查索引就绪标记，必要时从 MySQL 重建 Redis GEO。
     */
    @Scheduled(
            initialDelayString = "${citycopilot.geo.reconcile-interval-ms:60000}",
            fixedDelayString = "${citycopilot.geo.reconcile-interval-ms:60000}"
    )
    public void reconcileIfNecessary() {
        if (projectionProperties != null
                && !projectionProperties.getRoles().isReconciliationEnabled()) {
            return;
        }
        if (shopGeoIndexService.isReady()) {
            return;
        }
        try {
            // syncShopGeoIndex 在受保护的投影租约下发布新一代 GEO 索引。
            int indexed = shopGeoIndexService.syncShopGeoIndex();
            log.info("Reconciled Redis GEO shop index: indexed={}", indexed);
        } catch (RuntimeException error) {
            log.warn("Redis GEO index reconciliation failed; SQL fallback remains active: {}",
                    error.getMessage());
        }
    }
}
