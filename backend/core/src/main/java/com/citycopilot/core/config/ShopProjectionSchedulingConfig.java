package com.citycopilot.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "citycopilot.projection.roles",
        name = "projector-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ShopProjectionSchedulingConfig {
}
