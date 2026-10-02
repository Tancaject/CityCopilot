package com.citycopilot.core.config;

import com.citycopilot.core.service.ShopGeoIndexService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Core 启动后可选执行一次店铺 Redis GEO 索引同步。
 *
 * <p>同步失败只记录警告，店铺发现服务会根据就绪状态使用降级路径，避免阻止 Core 启动。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "citycopilot.geo", name = "auto-sync-shop-index", havingValue = "true", matchIfMissing = true)
public class ShopGeoIndexRunner implements ApplicationRunner {

    /** 构建和检查 GEO 投影的服务。 */
    @Resource
    private ShopGeoIndexService shopGeoIndexService;

    /** 可选投影角色配置；分离部署时用它禁止非对账实例启动同步。 */
    @Autowired(required = false)
    private ShopProjectionProperties projectionProperties;

    /**
     * Spring 启动完成后同步 GEO 投影。
     *
     * @param args 应用启动参数，本任务不解析这些参数
     */
    @Override
    public void run(ApplicationArguments args) {
        if (projectionProperties != null
                && !projectionProperties.getRoles().isReconciliationEnabled()) {
            return;
        }
        try {
            // syncShopGeoIndex 从 MySQL 权威店铺数据重建 Redis GEO，而不是反向覆盖数据库。
            int indexed = shopGeoIndexService.syncShopGeoIndex();
            log.info("Shop GEO index startup sync completed: indexed={}, ready={}",
                    indexed, shopGeoIndexService.isReady());
        } catch (Exception error) {
            log.warn("Shop GEO index startup sync failed; discovery will use its fallback: {}", error.getMessage());
        }
    }
}
