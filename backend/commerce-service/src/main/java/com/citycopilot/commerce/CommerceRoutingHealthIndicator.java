package com.citycopilot.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * 检查本实例的 Redis 路由配置是否与全局控制记录及各分片门禁一致。
 *
 * <p>不一致时返回 {@code OUT_OF_SERVICE}，使错误代际或排空中的实例退出业务流量。</p>
 */
@Component
final class CommerceRoutingHealthIndicator implements HealthIndicator {
    /** 读取并校验路由控制面的守卫。 */
    private final CommerceRoutingGuard routing;
    /** 当前进程是否承载准入 API；运维进程可关闭此能力。 */
    private final boolean apiEnabled;

    CommerceRoutingHealthIndicator(
            CommerceRoutingGuard routing,
            @Value("${citycopilot.commerce.api-enabled:true}") boolean apiEnabled
    ) {
        this.routing = routing;
        this.apiEnabled = apiEnabled;
    }

    /**
     * 汇总全局路由记录、本地代际配置和分片门禁状态。
     *
     * @return Actuator 健康结果；控制面不可读时直接返回 DOWN
     */
    @Override
    public Health health() {
        try {
            CommerceRoutingGuard.RoutingSnapshot current = routing.current();
            if (current == null) {
                return Health.down()
                        .withDetail("reason", "routing_control_missing")
                        .build();
            }
            Health.Builder health = Health.up()
                    .withDetail("state", current.state())
                    .withDetail("generation", current.generation())
                    .withDetail("physicalShardCount", current.physicalShardCount())
                    .withDetail("revision", current.revision());
            if (!routing.isLocal(current)) {
                // 当前实例不属于生效代际时必须摘流，不能访问另一代际的数据。
                return Health.status(Status.OUT_OF_SERVICE)
                        .withDetails(health.build().getDetails())
                        .withDetail("reason", "local_routing_config_mismatch")
                        .build();
            }
            if (!routing.localShardGatesMatch(current.state())) {
                // 全局状态与任一分片门禁不一致，说明切换尚未形成完整屏障。
                return Health.status(Status.OUT_OF_SERVICE)
                        .withDetails(health.build().getDetails())
                        .withDetail("reason", "shard_routing_gate_mismatch")
                        .build();
            }
            if (apiEnabled && current.state() == CommerceRoutingGuard.RoutingState.DRAINING) {
                return Health.status(Status.OUT_OF_SERVICE)
                        .withDetails(health.build().getDetails())
                        .withDetail("reason", "api_admission_draining")
                        .build();
            }
            return health.build();
        } catch (RuntimeException failure) {
            return Health.down()
                    .withDetail("reason", "routing_control_unavailable")
                    .build();
        }
    }
}
