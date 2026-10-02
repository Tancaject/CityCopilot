package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.config.ShopSearchProperties;
import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.entity.ShopType;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.mapper.ShopTypeMapper;
import com.citycopilot.core.projection.ShopProjectionLeaseService;
import com.citycopilot.core.projection.ShopProjectionOutboxRepository;
import com.citycopilot.core.projection.ShopProjectionRetirementRegistry;
import com.citycopilot.core.projection.ProjectionGenerationCleanupResult;
import com.citycopilot.core.utils.RedisGeoCoordinates;
import com.citycopilot.search.ShopSearchDocument;
import com.citycopilot.search.ShopSearchGateway;
import com.citycopilot.search.ShopSearchHit;
import com.citycopilot.search.ShopSearchRequest;
import com.citycopilot.search.ShopSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 店铺搜索投影服务，维护可重建的搜索索引并提供候选查询。
 *
 * <p>搜索引擎只负责召回和排序；店铺属性、资格、可下单性及投影版本仍以 MySQL 为准。</p>
 */
@Slf4j
@Service
public class ShopSearchProjectionService {

    /** 搜索模块网关，封装查询、单文档写入、代际重建和别名发布。 */
    private final ShopSearchGateway searchGateway;

    /** 店铺事实表 Mapper，用于投影构建和权威补水。 */
    private final ShopMapper shopMapper;

    /** 店铺分类 Mapper，用于补充搜索文档中的分类名称。 */
    private final ShopTypeMapper shopTypeMapper;

    /** 坐标半径和候选上限配置。 */
    private final GeoProperties geoProperties;

    /** 搜索读开关、模式和恢复探测间隔配置。 */
    private final ShopSearchProperties searchProperties;

    /**
     * 当前实例观察到的投影漂移标记。
     *
     * <p>初始为 false，并通过网关探测共享别名状态，避免其他实例完成重建后本实例仍永久降级。</p>
     */
    private final AtomicBoolean reconciliationRequired = new AtomicBoolean(false);

    /** 下次允许探测共享搜索别名的单调时钟时间，抑制故障期间的探测风暴。 */
    private final AtomicLong nextReadinessProbeAtNanos = new AtomicLong();

    /** 可选数据库租约服务，防止多个 Core 实例同时发布搜索代际。 */
    @Autowired(required = false)
    private ShopProjectionLeaseService projectionLeaseService;

    /** 可选投影租约和角色配置。 */
    @Autowired(required = false)
    private ShopProjectionProperties projectionProperties;

    /** 可选投影 Outbox，用于全量构建期间追平并发写入。 */
    @Autowired(required = false)
    private ShopProjectionOutboxRepository projectionOutboxRepository;

    @Autowired(required = false)
    private ShopProjectionRetirementRegistry retirementRegistry;

    /**
     * @param searchGateway 搜索模块网关
     * @param shopMapper 店铺 Mapper
     * @param shopTypeMapper 店铺分类 Mapper
     * @param geoProperties GEO 配置
     * @param searchProperties 搜索配置
     */
    public ShopSearchProjectionService(
            ShopSearchGateway searchGateway,
            ShopMapper shopMapper,
            ShopTypeMapper shopTypeMapper,
            GeoProperties geoProperties,
            ShopSearchProperties searchProperties
    ) {
        this.searchGateway = searchGateway;
        this.shopMapper = shopMapper;
        this.shopTypeMapper = shopTypeMapper;
        this.geoProperties = geoProperties;
        this.searchProperties = searchProperties;
    }

    /**
     * 判断查询是否需要全文搜索能力。
     *
     * @param query 店铺发现条件
     * @return 搜索读已启用且包含关键词或偏好提示时返回 {@code true}
     */
    public boolean shouldSearch(ShopDiscoveryQuery query) {
        return searchProperties.isSearchReadEnabled()
                && query != null
                && (query.hasKeyword() || query.hasPreferredCategory());
    }

    /**
     * 查询搜索投影，并按 MySQL 事实源补水、校验全部命中。
     *
     * @param query 发现条件
     * @return 可用候选，或携带降级原因的不可用结果
     */
    public SearchCandidates search(ShopDiscoveryQuery query) {
        if (!shouldSearch(query)) {
            return SearchCandidates.unavailable(ShopSearchResult.SEARCH_DISABLED);
        }
        if (!recoverSharedReadinessIfDue()) {
            return SearchCandidates.unavailable(ShopSearchResult.INDEX_STALE);
        }
        try {
            ShopSearchResult result = searchGateway.search(toRequest(query));
            if (result == null || !result.available()) {
                requireReconciliation();
                String reason = result == null
                        ? ShopSearchResult.SEARCH_UNAVAILABLE
                        : result.unavailableReason();
                return SearchCandidates.unavailable(reason);
            }
            HydrationResult hydration = hydrateAuthoritatively(query, result.hits());
            // 任一命中已删除、失去资格或重复都说明投影漂移，本次整批降级而非返回部分旧结果。
            if (hydration.drifted()) {
                requireReconciliation();
                return SearchCandidates.unavailable(ShopSearchResult.INDEX_STALE);
            }
            return SearchCandidates.available(hydration.candidates(), result.total());
        } catch (RuntimeException error) {
            requireReconciliation();
            log.warn("Shop search projection query failed; legacy discovery remains active: {}", error.getMessage());
            return SearchCandidates.unavailable(ShopSearchResult.SEARCH_UNAVAILABLE);
        }
    }

    /**
     * 根据 MySQL 最新状态尽力刷新单个搜索文档。
     *
     * <p>该兼容路径不向调用方传播投影失败，只标记后续全量对账。</p>
     *
     * @param shopId 店铺主键
     */
    public synchronized void upsertLatestBestEffort(Long shopId) {
        if (!searchProperties.isSearchReadEnabled() || shopId == null) {
            return;
        }
        try {
            Shop shop = shopMapper.selectById(shopId);
            if (shop == null) {
                requireReconciliation();
                return;
            }
            upsertVersioned(shop);
        } catch (RuntimeException error) {
            requireReconciliation();
            log.warn("Shop {} committed but search projection refresh failed: {}", shopId, error.getMessage());
        }
    }

    /**
     * 严格写入单个带版本搜索文档。
     *
     * <p>异常会向外传播，使投影 Outbox 保留该事件并重试。</p>
     *
     * @param shop 已从 MySQL 读取的最新店铺事实
     */
    public void upsertVersioned(Shop shop) {
        if (!searchProperties.isSearchReadEnabled() || shop == null) {
            return;
        }
        ShopType type = shop.getTypeId() == null ? null : shopTypeMapper.selectById(shop.getTypeId());
        searchGateway.upsert(toDocument(shop, type));
    }

    /**
     * 在写租约保护下全量重建搜索索引。
     *
     * @return 写入新索引的文档数
     * @throws IllegalStateException 其他实例持有投影写租约时抛出
     */
    public synchronized int rebuildAll() {
        if (!searchProperties.isSearchReadEnabled()) {
            return 0;
        }
        if (projectionLeaseService == null) {
            return rebuildAllUnderLease(null);
        }
        var acquired = projectionLeaseService.tryAcquire(
                ShopProjectionLeaseService.WRITER_LEASE,
                projectionProperties == null
                        ? java.time.Duration.ofMinutes(10)
                        : projectionProperties.getMaintenanceLeaseTtl()
        );
        if (acquired.isEmpty()) {
            throw new IllegalStateException("Another Core replica owns the shop projection writer lease");
        }
        try (ShopProjectionLeaseService.Lease lease = acquired.get()) {
            return rebuildAllUnderLease(lease);
        }
    }

    /**
     * 构建新搜索代际，并在可用时用 Outbox 水位追平并发写入后发布。
     *
     * @param lease 当前投影写租约；兼容模式可为空
     * @return 构建文档数
     */
    private int rebuildAllUnderLease(ShopProjectionLeaseService.Lease lease) {
        requireReconciliation();
        try {
            long baseWatermark = lease == null || projectionOutboxRepository == null
                    ? 0L
                    : closeWatermark(lease);
            long shopHighWatermark = shopMapper.projectionHighWatermark();
            int[] indexed = {0};
            // 复用待发布代际的批量写入口，不在 JVM 中收集全库快照。
            searchGateway.rebuild(List.of(), generation -> {
                indexed[0] = writeBasePages(generation, lease, shopHighWatermark);
                if (lease != null) {
                    lease.renew();
                    if (projectionOutboxRepository != null) {
                        catchUpAndPublish(generation, lease, baseWatermark);
                    } else {
                        lease.executeFenced(() -> publishAndRecordRetirement(generation));
                    }
                } else {
                    generation.publish();
                }
            });
            clearReconciliationRequired();
            return indexed[0];
        } catch (RuntimeException error) {
            requireReconciliation();
            throw error;
        }
    }

    private int writeBasePages(ShopSearchGateway.PendingGeneration generation,
                               ShopProjectionLeaseService.Lease lease, long highWatermark) {
        long cursor = 0L;
        int indexed = 0;
        while (cursor < highWatermark) {
            if (lease != null) lease.renew();
            List<Shop> shops = shopMapper.projectionPage(cursor, highWatermark);
            if (shops.isEmpty()) break;
            Map<Long, ShopType> types = typesFor(shops);
            List<ShopSearchDocument> documents = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                if (!StrUtil.isBlank(shop.getName())) documents.add(toDocument(shop, types.get(shop.getTypeId())));
            }
            generation.upsertAll(documents);
            indexed += documents.size();
            cursor = shops.get(shops.size() - 1).getId();
        }
        return indexed;
    }

    private Map<Long, ShopType> typesFor(List<Shop> shops) {
        LinkedHashSet<Long> typeIds = shops.stream().map(Shop::getTypeId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return typeIds.isEmpty() ? Map.of() : shopTypeMapper.selectBatchIds(typeIds).stream()
                .filter(type -> type != null && type.getId() != null)
                .collect(Collectors.toMap(ShopType::getId, Function.identity(), (left, right) -> left));
    }

    /**
     * 把基线之后的 Outbox 变化追入待发布代际，并在短暂切换屏障内追平尾部。
     *
     * @param generation 搜索网关创建的待发布代际
     * @param lease 投影写租约
     * @param baseWatermark 全量扫描开始前的 Outbox 水位
     */
    private void catchUpAndPublish(
            ShopSearchGateway.PendingGeneration generation,
            ShopProjectionLeaseService.Lease lease,
            long baseWatermark
    ) {
        // 先短暂封闭水位，再在不阻塞 API 写入时追大段增量；仅最后尾部在切换屏障内处理。
        long preCutoverWatermark = closeWatermark(lease);
        applySearchChanges(generation, lease, baseWatermark, preCutoverWatermark);
        lease.renew();
        lease.executeCutoverFenced(() -> {
            long finalWatermark = projectionOutboxRepository.highWatermark();
            applySearchChanges(generation, lease, preCutoverWatermark, finalWatermark);
            publishAndRecordRetirement(generation);
            return finalWatermark;
        });
    }

    private void publishAndRecordRetirement(ShopSearchGateway.PendingGeneration generation) {
        generation.publish();
        if (retirementRegistry != null) {
            for (String retired : generation.retiredGenerations()) {
                retirementRegistry.recordAfterPublication("search:" + searchProperties.getIndexAlias(), retired);
            }
        }
    }

    /** 仅供已排空读者的维护操作调用；每个精确索引删除都在 writer 行锁内重读 alias。 */
    public List<ProjectionGenerationCleanupResult> cleanupRetiredGenerations(ShopProjectionLeaseService.Lease lease,
                                        ShopProjectionProperties.Cleanup cleanup) {
        if (!searchProperties.isSearchReadEnabled()) return List.of();
        if (retirementRegistry == null) throw new IllegalStateException("Search retirement registry is unavailable");
        String projection = "search:" + searchProperties.getIndexAlias();
        List<ProjectionGenerationCleanupResult> results = new ArrayList<>();
        for (String generation : retirementRegistry.due(projection, cleanup.getRetention(), cleanup.getGenerationBatchSize())) {
            lease.renew();
            lease.executeFenced(() -> {
                ShopSearchGateway.CleanupDecision decision = searchGateway.deleteRetiredGeneration(generation);
                if (decision == ShopSearchGateway.CleanupDecision.REMOVED_OR_ABSENT) {
                    log.info("Retired search generation cleanup: generation={}, outcome={}", generation, decision);
                    retirementRegistry.forget(projection, generation);
                }
                results.add(new ProjectionGenerationCleanupResult("search", generation, decision.name(), 0));
            });
        }
        return List.copyOf(results);
    }

    /**
     * 在切换屏障内读取一个封闭 Outbox 水位。
     *
     * @param lease 当前投影租约
     * @return 屏障建立时的最大事件主键
     */
    private long closeWatermark(ShopProjectionLeaseService.Lease lease) {
        return lease.executeCutoverFenced(projectionOutboxRepository::highWatermark);
    }

    /**
     * 按批把指定水位区间的最新店铺版本写入待发布搜索代际。
     *
     * @param generation 待发布搜索代际
     * @param lease 当前写租约，每页续租后才继续读取与写入
     * @param exclusiveWatermark 不包含的起始事件主键
     * @param inclusiveWatermark 包含的结束事件主键
     */
    private void applySearchChanges(
            ShopSearchGateway.PendingGeneration generation,
            ShopProjectionLeaseService.Lease lease,
            long exclusiveWatermark,
            long inclusiveWatermark
    ) {
        long cursor = exclusiveWatermark;
        while (cursor < inclusiveWatermark) {
            lease.renew();
            List<ShopProjectionOutboxRepository.ProjectionChange> changes =
                    projectionOutboxRepository.changesAfter(cursor, inclusiveWatermark, 500);
            if (changes.isEmpty()) {
                return;
            }
            Map<Long, Long> requiredVersions = new LinkedHashMap<>();
            // 同一店铺在窗口内可能多次变更，只需追到最高聚合版本。
            for (ShopProjectionOutboxRepository.ProjectionChange change : changes) {
                requiredVersions.merge(change.shopId(), change.aggregateVersion(), Math::max);
            }
            List<Shop> currentShops = shopMapper.selectBatchIds(requiredVersions.keySet());
            Map<Long, Shop> shopsById = currentShops.stream()
                    .filter(shop -> shop != null && shop.getId() != null)
                    .collect(Collectors.toMap(Shop::getId, Function.identity(), (left, right) -> left));
            if (shopsById.size() != requiredVersions.size()) {
                throw new IllegalStateException(
                        "Shop deletion projection is not implemented during search rebuild catch-up");
            }
            Map<Long, ShopType> currentTypes = typesFor(currentShops);
            List<ShopSearchDocument> catchUpDocuments = new ArrayList<>(currentShops.size());
            for (Map.Entry<Long, Long> required : requiredVersions.entrySet()) {
                Shop shop = shopsById.get(required.getKey());
                long currentVersion = Objects.requireNonNullElse(shop.getProjectionVersion(), 0L);
                if (currentVersion < required.getValue()) {
                    throw new IllegalStateException(
                            "Shop source version is behind rebuild watermark: " + shop.getId());
                }
                catchUpDocuments.add(toDocument(shop, currentTypes.get(shop.getTypeId())));
            }
            generation.upsertAll(catchUpDocuments);
            long nextCursor = changes.get(changes.size() - 1).id();
            if (nextCursor <= cursor) {
                throw new IllegalStateException("Shop projection catch-up cursor did not advance");
            }
            cursor = nextCursor;
        }
    }

    /**
     * 判断当前实例或共享搜索别名是否需要重建。
     *
     * @return 对账功能启用且投影漂移或网关未就绪时返回 {@code true}
     */
    public boolean needsReconciliation() {
        if (!searchProperties.isReconciliationEnabled()) {
            return false;
        }
        if (reconciliationRequired.get()) {
            return true;
        }
        try {
            return !searchGateway.isReady();
        } catch (RuntimeException error) {
            requireReconciliation();
            return true;
        }
    }

    /** 标记搜索投影需要对账，供提交后刷新失败等调用点使用。 */
    public void markReconciliationRequired() {
        requireReconciliation();
    }

    /**
     * 按探测间隔检查共享别名是否已被其他实例修复。
     *
     * @return 当前是否可安全读取搜索投影
     */
    private boolean recoverSharedReadinessIfDue() {
        if (!reconciliationRequired.get()) {
            return true;
        }
        long now = System.nanoTime();
        long scheduled = nextReadinessProbeAtNanos.get();
        if (scheduled != 0L && now - scheduled < 0L) {
            return false;
        }
        long delayMs = Math.max(0L, Objects.requireNonNullElse(
                searchProperties.getRecoveryProbeIntervalMs(), 5_000L));
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMs);
        long next = now + delayNanos;
        if (!nextReadinessProbeAtNanos.compareAndSet(scheduled, next)) {
            return !reconciliationRequired.get();
        }
        try {
            if (searchGateway.probeSharedReadiness()) {
                clearReconciliationRequired();
                return true;
            }
        } catch (RuntimeException error) {
            log.debug("Shop search shared readiness probe failed: {}", error.getMessage());
        }
        return false;
    }

    /** 设置本地漂移标记并安排下一次共享就绪探测。 */
    private void requireReconciliation() {
        reconciliationRequired.set(true);
        long delayMs = Math.max(0L, Objects.requireNonNullElse(
                searchProperties.getRecoveryProbeIntervalMs(), 5_000L));
        long scheduled = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        nextReadinessProbeAtNanos.compareAndSet(0L, scheduled);
    }

    /** 清除本地漂移标记和探测时间。 */
    private void clearReconciliationRequired() {
        reconciliationRequired.set(false);
        nextReadinessProbeAtNanos.set(0L);
    }

    /**
     * 将 MySQL 店铺和分类映射为版本化搜索文档。
     *
     * @param shop 店铺事实
     * @param type 可选店铺分类
     * @return 可写入搜索索引的文档
     */
    ShopSearchDocument toDocument(Shop shop, ShopType type) {
        Objects.requireNonNull(shop, "shop must not be null");
        boolean validLocation = RedisGeoCoordinates.isValid(shop.getX(), shop.getY());
        return ShopSearchDocument.builder()
                .id(shop.getId())
                .name(shop.getName())
                .typeId(shop.getTypeId())
                .typeName(type == null ? null : type.getName())
                .area(shop.getArea())
                .address(shop.getAddress())
                .longitude(validLocation ? shop.getX() : null)
                .latitude(validLocation ? shop.getY() : null)
                .avgPrice(shop.getAvgPrice())
                .score(shop.getScore())
                .sold(shop.getSold())
                .openHours(shop.getOpenHours())
                .geoStatus(shop.getGeoStatus())
                .updateTime(shop.getUpdateTime())
                .projectionVersion(shop.getProjectionVersion())
                .build();
    }

    /**
     * 把统一发现条件转换成搜索模块请求。
     *
     * @param query 店铺发现条件
     * @return 搜索请求，坐标合法时包含半径过滤
     */
    private ShopSearchRequest toRequest(ShopDiscoveryQuery query) {
        ShopSearchRequest.Builder builder = ShopSearchRequest.builder()
                .keyword(query.normalizedKeyword())
                .preferredCategory(query.normalizedPreferredCategory())
                .typeId(query.getTypeId() == null ? null : query.getTypeId().longValue())
                .offset(0)
                .size(query.resolveCandidateLimit(geoProperties));
        if (query.hasValidPoint()) {
            builder.longitude(query.getX())
                    .latitude(query.getY())
                    .radiusMeters(resolveRadiusMeters(query));
        }
        return builder.build();
    }

    /**
     * 按搜索命中顺序从 MySQL 批量补水，并验证每条记录仍符合当前查询资格。
     *
     * @param query 原始查询条件
     * @param hits 搜索引擎命中
     * @return 候选及是否检测到投影漂移
     */
    private HydrationResult hydrateAuthoritatively(
            ShopDiscoveryQuery query,
            List<ShopSearchHit> hits
    ) {
        if (hits == null || hits.isEmpty()) {
            return HydrationResult.current(List.of());
        }
        LinkedHashSet<Long> orderedIds = hits.stream()
                .filter(Objects::nonNull)
                .map(ShopSearchHit::document)
                .filter(Objects::nonNull)
                .map(ShopSearchDocument::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderedIds.isEmpty()) {
            return HydrationResult.drifted(List.of());
        }
        Map<Long, Shop> currentShops = shopMapper.selectBatchIds(orderedIds).stream()
                .filter(shop -> shop != null && shop.getId() != null)
                .collect(Collectors.toMap(Shop::getId, Function.identity(), (left, right) -> left));

        List<SearchCandidate> candidates = new ArrayList<>(orderedIds.size());
        int searchRank = 0;
        for (Long id : orderedIds) {
            Shop shop = currentShops.get(id);
            if (!isAuthoritativelyEligible(query, shop)) {
                searchRank++;
                continue;
            }
            Double distance = query.hasValidPoint()
                    ? DiscoveryGeoUtils.distanceMeters(query.getX(), query.getY(), shop.getX(), shop.getY())
                    : null;
            if (distance == null || distance <= resolveRadiusMeters(query)) {
                candidates.add(new SearchCandidate(shop, distance, searchRank));
            }
            searchRank++;
        }
        boolean drifted = candidates.size() != orderedIds.size()
                || orderedIds.size() != hits.size();
        return new HydrationResult(List.copyOf(candidates), drifted);
    }

    /**
     * 使用 MySQL 当前事实检查搜索命中的分类与坐标资格。
     *
     * @param query 原始查询条件
     * @param shop 当前店铺事实
     * @return 该命中是否仍可进入发现候选
     */
    private boolean isAuthoritativelyEligible(ShopDiscoveryQuery query, Shop shop) {
        if (shop == null) {
            return false;
        }
        if (query.getTypeId() != null && !Objects.equals(shop.getTypeId(), query.getTypeId().longValue())) {
            return false;
        }
        if (!query.hasValidPoint()) {
            return true;
        }
        return "verified".equals(shop.getGeoStatus())
                && RedisGeoCoordinates.isValid(shop.getX(), shop.getY());
    }

    private double resolveRadiusMeters(ShopDiscoveryQuery query) {
        if (query.getRadiusMeters() != null) return query.getRadiusMeters();
        Double configured = geoProperties == null ? null : geoProperties.getNearbyRadiusMeters();
        double resolved = Objects.requireNonNullElse(configured, 5000D);
        return resolved > 0D ? resolved : 5000D;
    }

    /**
     * 经 MySQL 权威补水后的搜索候选。
     *
     * @param shop 当前店铺事实
     * @param distanceMeters 可选距离，单位为米
     * @param searchRank 搜索引擎原始零基排名
     */
    public record SearchCandidate(Shop shop, Double distanceMeters, int searchRank) {
    }

    /**
     * 内部补水结果。
     *
     * @param candidates 权威候选
     * @param drifted 是否发现缺失、重复或失效命中
     */
    private record HydrationResult(List<SearchCandidate> candidates, boolean drifted) {

        private static HydrationResult current(List<SearchCandidate> candidates) {
            return new HydrationResult(candidates, false);
        }

        private static HydrationResult drifted(List<SearchCandidate> candidates) {
            return new HydrationResult(candidates, true);
        }
    }

    /**
     * 对本地发现 Provider 暴露的搜索结果。
     *
     * @param available 本次是否可使用搜索候选
     * @param candidates 权威补水后的候选
     * @param total 搜索引擎报告的总命中数
     * @param unavailableReason 不可用时的稳定原因码
     */
    public record SearchCandidates(
            boolean available,
            List<SearchCandidate> candidates,
            long total,
            String unavailableReason
    ) {
        public SearchCandidates {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static SearchCandidates available(List<SearchCandidate> candidates, long total) {
            return new SearchCandidates(true, candidates, Math.max(0L, total), null);
        }

        public static SearchCandidates unavailable(String reason) {
            return new SearchCandidates(false, List.of(), 0L,
                    StrUtil.blankToDefault(reason, ShopSearchResult.SEARCH_UNAVAILABLE));
        }
    }
}
