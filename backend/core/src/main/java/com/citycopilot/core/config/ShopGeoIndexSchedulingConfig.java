package com.citycopilot.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "citycopilot.geo",
        name = "reconcile-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ShopGeoIndexSchedulingConfig {
}
