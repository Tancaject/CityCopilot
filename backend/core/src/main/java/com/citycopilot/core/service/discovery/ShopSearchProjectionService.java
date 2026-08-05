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
 * Maintains and queries the rebuildable shop search projection. MySQL remains
 * authoritative for shop attributes, eligibility, and orderability.
 */
@Slf4j
@Service
public class ShopSearchProjectionService {

    private final ShopSearchGateway searchGateway;
    private final ShopMapper shopMapper;
    private final ShopTypeMapper shopTypeMapper;
    private final GeoProperties geoProperties;
    private final ShopSearchProperties searchProperties;
    // Shared alias readiness is checked by the gateway. Starting false avoids
    // pinning API-only replicas in fallback after another replica rebuilt it.
    private final AtomicBoolean reconciliationRequired = new AtomicBoolean(false);
    private final AtomicLong nextReadinessProbeAtNanos = new AtomicLong();

    @Autowired(required = false)
    private ShopProjectionLeaseService projectionLeaseService;

    @Autowired(required = false)
    private ShopProjectionProperties projectionProperties;

    @Autowired(required = false)
    private ShopProjectionOutboxRepository projectionOutboxRepository;

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

    public boolean shouldSearch(ShopDiscoveryQuery query) {
        return searchProperties.isSearchReadEnabled()
                && query != null
                && (query.hasKeyword() || query.hasPreferredCategory());
    }

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

    /** Strict projector path: failures escape so the outbox row is retried. */
    public void upsertVersioned(Shop shop) {
        if (!searchProperties.isSearchReadEnabled() || shop == null) {
            return;
        }
        ShopType type = shop.getTypeId() == null ? null : shopTypeMapper.selectById(shop.getTypeId());
        searchGateway.upsert(toDocument(shop, type));
    }

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

    private int rebuildAllUnderLease(ShopProjectionLeaseService.Lease lease) {
        requireReconciliation();
        try {
            long baseWatermark = lease == null || projectionOutboxRepository == null
                    ? 0L
                    : closeWatermark(lease);
            Map<Long, ShopType> types = shopTypeMapper.selectList(null).stream()
                    .filter(type -> type.getId() != null)
                    .collect(Collectors.toMap(ShopType::getId, Function.identity(), (left, right) -> left));
            List<ShopSearchDocument> documents = new ArrayList<>();
            for (Shop shop : shopMapper.selectList(null)) {
                if (shop == null || shop.getId() == null || StrUtil.isBlank(shop.getName())) {
                    continue;
                }
                documents.add(toDocument(shop, types.get(shop.getTypeId())));
            }
            if (lease == null || projectionOutboxRepository == null) {
                searchGateway.rebuild(documents);
            } else {
                lease.renew();
                searchGateway.rebuild(documents,
                        generation -> catchUpAndPublish(generation, lease, baseWatermark));
            }
            clearReconciliationRequired();
            return documents.size();
        } catch (RuntimeException error) {
            requireReconciliation();
            throw error;
        }
    }

    private void catchUpAndPublish(
            ShopSearchGateway.PendingGeneration generation,
            ShopProjectionLeaseService.Lease lease,
            long baseWatermark
    ) {
        // First close a watermark briefly, then apply the usually larger delta
        // without blocking API writes. Only the final tail is applied while
        // the cutover barrier is held.
        long preCutoverWatermark = closeWatermark(lease);
        applySearchChanges(generation, baseWatermark, preCutoverWatermark);
        lease.renew();
        lease.executeCutoverFenced(() -> {
            long finalWatermark = projectionOutboxRepository.highWatermark();
            applySearchChanges(generation, preCutoverWatermark, finalWatermark);
            generation.publish();
            return finalWatermark;
        });
    }

    private long closeWatermark(ShopProjectionLeaseService.Lease lease) {
        return lease.executeCutoverFenced(projectionOutboxRepository::highWatermark);
    }

    private void applySearchChanges(
            ShopSearchGateway.PendingGeneration generation,
            long exclusiveWatermark,
            long inclusiveWatermark
    ) {
        long cursor = exclusiveWatermark;
        while (cursor < inclusiveWatermark) {
            List<ShopProjectionOutboxRepository.ProjectionChange> changes =
                    projectionOutboxRepository.changesAfter(cursor, inclusiveWatermark, 500);
            if (changes.isEmpty()) {
                return;
            }
            Map<Long, Long> requiredVersions = new LinkedHashMap<>();
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
            LinkedHashSet<Long> typeIds = currentShops.stream()
                    .map(Shop::getTypeId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<Long, ShopType> currentTypes = typeIds.isEmpty()
                    ? Map.of()
                    : shopTypeMapper.selectBatchIds(typeIds).stream()
                            .filter(type -> type != null && type.getId() != null)
                            .collect(Collectors.toMap(
                                    ShopType::getId,
                                    Function.identity(),
                                    (left, right) -> left));
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

    public void markReconciliationRequired() {
        requireReconciliation();
    }

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

    private void requireReconciliation() {
        reconciliationRequired.set(true);
        long delayMs = Math.max(0L, Objects.requireNonNullElse(
                searchProperties.getRecoveryProbeIntervalMs(), 5_000L));
        long scheduled = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        nextReadinessProbeAtNanos.compareAndSet(0L, scheduled);
    }

    private void clearReconciliationRequired() {
        reconciliationRequired.set(false);
        nextReadinessProbeAtNanos.set(0L);
    }

    ShopSearchDocument toDocument(Shop shop, ShopType type) {
        Objects.requireNonNull(shop, "shop must not be null");
        boolean validLocation = DiscoveryGeoUtils.isValidCoordinate(shop.getX(), shop.getY());
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
                    .radiusMeters(resolveRadiusMeters());
        }
        return builder.build();
    }

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
            if (distance == null || distance <= resolveRadiusMeters()) {
                candidates.add(new SearchCandidate(shop, distance, searchRank));
            }
            searchRank++;
        }
        boolean drifted = candidates.size() != orderedIds.size()
                || orderedIds.size() != hits.size();
        return new HydrationResult(List.copyOf(candidates), drifted);
    }

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
                && DiscoveryGeoUtils.isValidCoordinate(shop.getX(), shop.getY());
    }

    private double resolveRadiusMeters() {
        Double configured = geoProperties == null ? null : geoProperties.getNearbyRadiusMeters();
        double resolved = Objects.requireNonNullElse(configured, 5000D);
        return resolved > 0D ? resolved : 5000D;
    }

    public record SearchCandidate(Shop shop, Double distanceMeters, int searchRank) {
    }

    private record HydrationResult(List<SearchCandidate> candidates, boolean drifted) {

        private static HydrationResult current(List<SearchCandidate> candidates) {
            return new HydrationResult(candidates, false);
        }

        private static HydrationResult drifted(List<SearchCandidate> candidates) {
            return new HydrationResult(candidates, true);
        }
    }

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
