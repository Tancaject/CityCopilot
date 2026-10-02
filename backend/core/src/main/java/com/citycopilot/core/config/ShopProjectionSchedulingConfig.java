package com.citycopilot.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 仅在当前 Core 实例承担 projector 角色时启用投影消费调度。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "citycopilot.projection.roles",
        name = "projector-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ShopProjectionSchedulingConfig {
}
