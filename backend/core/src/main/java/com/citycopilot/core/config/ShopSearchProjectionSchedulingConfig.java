package com.citycopilot.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "citycopilot.search",
        name = "enabled",
        havingValue = "true"
)
public class ShopSearchProjectionSchedulingConfig {
}
