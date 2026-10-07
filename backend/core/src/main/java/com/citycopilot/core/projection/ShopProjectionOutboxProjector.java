package com.citycopilot.core.projection;

import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.citycopilot.core.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ShopChanged 至少一次增量投影器。
 *
 * <p>共享 MySQL fencing 租约将本写者与所有 Core 副本上的 GEO 全量重建串行化。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.projection.roles",
        name = "projector-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ShopProjectionOutboxProjector {

    /** 投影 Outbox 仓储，负责抢占、完成和失败退避。 */
    private final ShopProjectionOutboxRepository outbox;

    /** 共享投影写租约服务。 */
    private final ShopProjectionLeaseService leases;

    /** 店铺事实表 Mapper，用于读取事件对应的最新版本。 */
    private final ShopMapper shopMapper;

    /** Redis GEO 版本化投影目标。 */
    private final ShopGeoIndexService geoIndex;

    /** 轮询批量、claim TTL、租约 TTL 和最大重试延迟配置。 */
    private final ShopProjectionProperties properties;
    private final CacheClient cache;

    /**
     * @param outbox 投影 Outbox 仓储
     * @param leases 投影租约服务
     * @param shopMapper 店铺 Mapper
     * @param geoIndex GEO 投影服务
     * @param properties 投影配置
     */
    public ShopProjectionOutboxProjector(
            ShopProjectionOutboxRepository outbox,
            ShopProjectionLeaseService leases,
            ShopMapper shopMapper,
            ShopGeoIndexService geoIndex,
            ShopProjectionProperties properties,
            CacheClient cache
    ) {
        this.outbox = outbox;
        this.leases = leases;
        this.shopMapper = shopMapper;
        this.geoIndex = geoIndex;
        this.properties = properties;
        this.cache = cache;
    }

    /**
     * 定时抢占一批可处理事件，并在共享写租约内逐条推进。
     */
    @Scheduled(
            initialDelayString = "${citycopilot.projection.projector-poll-ms:250}",
            fixedDelayString = "${citycopilot.projection.projector-poll-ms:250}")
    public void projectReadyEvents() {
        var acquired = leases.tryAcquire(
                ShopProjectionLeaseService.WRITER_LEASE,
                properties.getProjectorLeaseTtl());
        if (acquired.isEmpty()) {
            return;
        }
        try (ShopProjectionLeaseService.Lease lease = acquired.get()) {
            // claim TTL 允许进程崩溃后由后继 Projector 重新接管未完成事件。
            List<ShopProjectionOutboxRepository.ClaimedEvent> events = outbox.claimReady(
                    properties.getProjectorBatchSize(),
                    "projector-" + lease.fencingToken(),
                    Math.max(1L, properties.getClaimTtl().toNanos() / 1_000L));
            for (ShopProjectionOutboxRepository.ClaimedEvent event : events) {
                projectOne(event, lease);
            }
        } catch (RuntimeException error) {
            log.warn("Shop projection poll failed: {}", error.getMessage());
        }
    }

    /**
     * 将一条事件的最新店铺事实幂等写入 GEO 投影。
     *
     * @param event 当前已抢占事件
     * @param lease 当前投影写租约
     */
    private void projectOne(
            ShopProjectionOutboxRepository.ClaimedEvent event,
            ShopProjectionLeaseService.Lease lease
    ) {
        try {
            lease.renew();
            Shop current = shopMapper.selectById(event.shopId());
            if (current == null) {
                throw new IllegalStateException("Shop deletion projection is not implemented: " + event.shopId());
            }
            long currentVersion = current.getProjectionVersion() == null
                    ? 0L
                    : current.getProjectionVersion();
            if (currentVersion < event.aggregateVersion()) {
                throw new IllegalStateException(
                        "Shop source version is behind outbox event: " + event.eventId());
            }

            // 可安全重试：GEO 在活跃代际保存最后应用的源版本，旧版本不会覆盖新版本。
            geoIndex.refreshShopGeoIndexVersioned(current, currentVersion);
            lease.assertHeld();
            cache.invalidateShop(event.shopId());
            outbox.complete(event);
        } catch (ShopProjectionOutboxRepository.ClaimLostException error) {
            throw error;
        } catch (RuntimeException error) {
            outbox.fail(event, error, properties.getMaxRetryDelay());
            log.warn("Shop projection event {} failed: {}", event.eventId(), error.getMessage());
        }
    }
}
