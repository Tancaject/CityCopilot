package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 绑定 {@code citycopilot.projection}，把 API 写入、持久投影消费与对账职责拆分到不同 Core 实例。
 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.projection")
public class ShopProjectionProperties {

    /** 在业务事务中追加 ShopChanged Outbox，避免同时双写 MySQL 与投影。 */
    private boolean outboxEnabled = true;

    /** 仅供兼容旧部署的事务后直写开关，生产环境应保持关闭。 */
    private boolean legacyAfterCommitEnabled = false;

    /** 当前实例承担的 API、投影消费和对账角色。 */
    private Roles roles = new Roles();
    /** 投影消费者单次认领的 Outbox 数量。 */
    private int projectorBatchSize = 50;
    /** 没有待处理记录时的轮询间隔，单位毫秒。 */
    private long projectorPollMs = 250L;
    /** 单条 Outbox 认领有效期。 */
    private Duration claimTtl = Duration.ofSeconds(30);
    /** 投影消费者实例租约有效期。 */
    private Duration projectorLeaseTtl = Duration.ofSeconds(30);
    /** 全量重建或维护操作租约有效期。 */
    private Duration maintenanceLeaseTtl = Duration.ofMinutes(10);
    private Cleanup cleanup = new Cleanup();
    /**
     * 指数重试退避上限；投影基础设施失败会持续重试，不因长时间故障转成终态死信。
     */
    private Duration maxRetryDelay = Duration.ofMinutes(15);

    /** 只在已排空读者的维护窗口回收已登记退役代际。 */
    @Data
    public static class Cleanup {
        private boolean enabled;
        private Duration retention = Duration.ZERO;
        private Duration readerGrace = Duration.ZERO;
        private int generationBatchSize = 10;
        private int keyBatchSize = 500;

        public void requireMaintenanceWindow() {
            if (retention == null || readerGrace == null
                    || retention.isZero() || retention.isNegative()
                    || readerGrace.isZero() || readerGrace.isNegative()
                    || retention.toMillis() == 0 || readerGrace.toMillis() == 0
                    || retention.compareTo(readerGrace) < 0) {
                throw new IllegalStateException("Projection cleanup requires positive explicit retention and reader-grace, "
                        + "and retention >= reader-grace");
            }
            if (generationBatchSize < 1 || generationBatchSize > 100
                    || keyBatchSize < 1 || keyBatchSize > 1000) {
                throw new IllegalStateException("Projection cleanup generation-batch-size must be 1..100 "
                        + "and key-batch-size must be 1..1000");
            }
        }
    }

    /** 可独立部署到不同 Core 副本的运行角色开关。 */
    @Data
    public static class Roles {
        /** 是否接收店铺 API 写请求。 */
        private boolean apiEnabled = true;
        /** 是否消费 ShopChanged Outbox 并更新投影。 */
        private boolean projectorEnabled = true;
        /** 是否执行全量投影对账和修复。 */
        private boolean reconciliationEnabled = true;
    }
}
