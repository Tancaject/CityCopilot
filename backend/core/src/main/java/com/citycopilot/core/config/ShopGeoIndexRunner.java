package com.citycopilot.core.config;

import com.citycopilot.core.service.ShopGeoIndexService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "citycopilot.geo", name = "auto-sync-shop-index", havingValue = "true", matchIfMissing = true)
public class ShopGeoIndexRunner implements ApplicationRunner {

    @Resource
    private ShopGeoIndexService shopGeoIndexService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            shopGeoIndexService.syncShopGeoIndex();
        } catch (Exception error) {
            log.warn("Shop GEO index sync skipped: {}", error.getMessage());
        }
    }
}
