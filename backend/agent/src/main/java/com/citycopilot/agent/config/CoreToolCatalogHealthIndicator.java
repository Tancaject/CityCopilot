package com.citycopilot.agent.config;

import com.citycopilot.agent.gateway.RemoteToolGateway;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Core 工具目录健康检查器。
 *
 * <p>主动触发目录刷新并区分“目录为空”“缓存已过期”和“Core 不可达”，供部署探针判断 Agent 是否具备可执行工具。</p>
 */
@Component("coreTools")
@ConditionalOnProperty(
        prefix = "citycopilot.health.core-tools",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CoreToolCatalogHealthIndicator implements HealthIndicator {
    /** 负责拉取并缓存 Core 工具元数据的远程网关。 */
    private final RemoteToolGateway toolGateway;

    public CoreToolCatalogHealthIndicator(RemoteToolGateway toolGateway) {
        this.toolGateway = toolGateway;
    }

    /**
     * 检查当前目录可用性并附带最近刷新时间与失败码。
     *
     * @return Actuator 健康结果；无可用且新鲜的工具目录时为 DOWN
     */
    @Override
    public Health health() {
        try {
            toolGateway.tools();
            RemoteToolGateway.CatalogStatus status = toolGateway.catalogStatus();
            Health.Builder health;
            if (status.toolCount() == 0) {
                health = Health.down()
                        .withDetail("reason", "CORE_TOOL_CATALOG_EMPTY")
                        .withDetail("fresh", status.fresh());
            } else if (!status.fresh()) {
                health = Health.down()
                        .withDetail("reason", "CORE_TOOL_CATALOG_STALE")
                        .withDetail("fresh", false);
            } else {
                health = Health.up()
                        .withDetail("fresh", true);
            }
            health.withDetail("toolCount", status.toolCount())
                    .withDetail("lastRefreshOutcome", status.lastRefreshOutcome().name());
            addInstant(health, "lastRefreshAttemptAt", status.lastRefreshAttemptAt());
            addInstant(health, "lastSuccessfulRefreshAt", status.lastSuccessfulRefreshAt());
            addInstant(health, "lastFailedRefreshAt", status.lastFailedRefreshAt());
            addInstant(health, "freshUntil", status.freshUntil());
            addInstant(health, "nextRefreshAt", status.nextRefreshAt());
            if (status.lastFailureCode() != null) {
                health.withDetail("lastFailureCode", status.lastFailureCode());
            }
            return health.build();
        } catch (Exception error) {
            return Health.down(error)
                    .withDetail("reason", "CORE_TOOL_CATALOG_UNAVAILABLE")
                    .build();
        }
    }

    /** 仅在时间存在时写入健康详情，避免探针输出无意义的 null。 */
    private static void addInstant(Health.Builder health, String name, java.time.Instant value) {
        if (value != null) {
            health.withDetail(name, value.toString());
        }
    }
}
