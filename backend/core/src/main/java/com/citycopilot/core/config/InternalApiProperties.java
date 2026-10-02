package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 绑定 {@code citycopilot.internal-api}，配置内部服务认证和幂等结果保留时间。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.internal-api")
public class InternalApiProperties {
    /** 兼容旧部署的单一服务令牌。 */
    private String serviceToken;
    /** 推荐使用的“服务名 -> 令牌”映射，可识别真实调用方。 */
    private Map<String, String> serviceTokens = new LinkedHashMap<>();
    /** 轮换窗口内额外接受的凭据；仅对仍配置当前令牌的同名 actor 生效。 */
    private Map<String, String> previousServiceTokens = new LinkedHashMap<>();
    /** 内部工具幂等结果在 Redis 中的保留时间。 */
    private Duration idempotencyTtl = Duration.ofHours(24);
}
