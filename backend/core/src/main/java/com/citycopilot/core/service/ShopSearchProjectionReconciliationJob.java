package com.citycopilot.core.service;

import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.service.discovery.ShopSearchProjectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 店铺全文搜索投影定时对账任务。
 *
 * <p>只在投影被标记漂移或不可用时重建，失败期间本地发现流程继续承担降级查询。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.search",
        name = "enabled",
        havingValue = "true"
)
public class ShopSearchProjectionReconciliationJob {

    /** 搜索投影服务，负责漂移判断和别名切换式全量重建。 */
    private final ShopSearchProjectionService projectionService;

    /** 可选投影角色配置，用于限制只有指定实例执行对账。 */
    @Autowired(required = false)
    private ShopProjectionProperties projectionProperties;

    /** @param projectionService 搜索投影服务 */
    public ShopSearchProjectionReconciliationJob(ShopSearchProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    /** 按配置周期检查漂移标记，并在需要时触发搜索索引重建。 */
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
            // rebuildAll 使用新索引承接全量数据，完成追平后再发布读别名。
            int indexed = projectionService.rebuildAll();
            log.info("Reconciled Elasticsearch shop projection: indexed={}", indexed);
        } catch (RuntimeException error) {
            log.warn("Elasticsearch shop projection reconciliation failed; legacy discovery remains active: {}",
                    error.getMessage());
        }
    }
}
