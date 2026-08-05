package com.citycopilot.core.service;

import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.projection.ShopProjectionLeaseService;
import com.citycopilot.core.projection.ShopProjectionOutboxRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_ALL_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_INDEX_META_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_INDEX_READY_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class ShopGeoIndexService {

    private static final DefaultRedisScript<Long> VERSIONED_REFRESH_SCRIPT =
            new DefaultRedisScript<>("""
                    local member = ARGV[1]
                    local incoming = tonumber(ARGV[2])
                    local currentRaw = redis.call('hget', KEYS[1], member)
                    local current = currentRaw and tonumber(currentRaw) or -1
                    if current >= incoming then return 0 end
                    local oldType = redis.call('hget', KEYS[2], member)
                    redis.call('zrem', KEYS[3], member)
                    if oldType then redis.call('zrem', ARGV[7] .. oldType, member) end
                    if ARGV[3] == '1' then
                      redis.call('geoadd', KEYS[3], ARGV[4], ARGV[5], member)
                      redis.call('geoadd', KEYS[4], ARGV[4], ARGV[5], member)
                      redis.call('hset', KEYS[2], member, ARGV[6])
                    else
                      redis.call('hdel', KEYS[2], member)
                    end
                    redis.call('hset', KEYS[1], member, incoming)
                    return 1
                    """, Long.class);

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private ShopProjectionLeaseService projectionLeaseService;

    @Autowired(required = false)
    private ShopProjectionProperties projectionProperties;

    @Autowired(required = false)
    private ShopProjectionOutboxRepository projectionOutboxRepository;

    /**
     * Prevents this process from trusting an old remote ready marker after a
     * Redis outage made an incremental update fail.
     */
    private volatile boolean locallyStale;

    /**
     * Rebuilds the complete index under a local lock. This is sufficient while
     * Core has a single writer instance and also serializes transaction
     * after-commit updates with a rebuild.
     */
    public synchronized int syncShopGeoIndex() {
        if (projectionLeaseService != null) {
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
                return syncGeneration(lease);
            }
        }
        return syncLegacyIndex();
    }

    private int syncLegacyIndex() {
        locallyStale = true;
        try {
            markRebuilding();
            List<Shop> shops = shopMapper.selectList(null);
            deleteGeoDataKeys();

            int indexed = 0;
            for (Shop shop : shops) {
                if (!isIndexable(shop)) {
                    continue;
                }
                addToIndexes(shop);
                indexed++;
            }
            markReady(indexed);
            log.info("Synced {} shops into Redis GEO indexes", indexed);
            return indexed;
        } catch (RuntimeException error) {
            markFailedSafely(error);
            throw error;
        }
    }

    /**
     * Builds isolated keys and publishes their generation only after the DB
     * fencing lease is revalidated. Existing readers remain on the old
     * generation throughout the rebuild.
     */
    private int syncGeneration(ShopProjectionLeaseService.Lease lease) {
        locallyStale = true;
        String generation = "v2:" + lease.fencingToken() + ":" + UUID.randomUUID();
        try {
            long baseWatermark = projectionOutboxRepository == null
                    ? 0L
                    : closeWatermark(lease);
            markGenerationBuilding(generation, lease.fencingToken());
            List<Shop> shops = shopMapper.selectList(null);
            int indexed = 0;
            for (Shop shop : shops) {
                if (!isIndexable(shop)) {
                    continue;
                }
                addToGeneration(shop, generation);
                writeGenerationState(shop, generation);
                indexed++;
            }
            lease.renew();
            int publishedCount = indexed;
            if (projectionOutboxRepository == null) {
                lease.executeFenced(() -> markGenerationReady(
                        generation, publishedCount, lease.fencingToken()));
            } else {
                catchUpAndPublishGeneration(
                        generation, publishedCount, lease, baseWatermark);
            }
            locallyStale = false;
            log.info("Published Redis GEO generation {} with {} shops", generation, indexed);
            return indexed;
        } catch (RuntimeException error) {
            markGenerationFailedSafely(generation, error);
            throw error;
        }
    }

    /**
     * Applies one committed MySQL change to the Redis GEO indexes.
     */
    public synchronized void refreshShopGeoIndex(Shop before, Shop after) {
        String previousGeneration = readyGeneration();
        if (previousGeneration == null) {
            locallyStale = true;
            return;
        }
        locallyStale = true;
        try {
            stringRedisTemplate.delete(SHOP_GEO_INDEX_READY_KEY);
            applyIncrementalRefresh(before, after);
            markIncrementalRefreshReady();
        } catch (RuntimeException error) {
            markStaleSafely(error);
            throw error;
        }
    }

    /**
     * Projector-only update. The caller owns the shared projection writer
     * lease, and Redis-stored versions make retries idempotent.
     */
    public synchronized void refreshShopGeoIndexVersioned(Shop after, long aggregateVersion) {
        if (after == null || after.getId() == null) {
            throw new IllegalArgumentException("shop and id are required");
        }
        String generation = readyGeneration();
        if (generation == null || !generation.startsWith("v2:")) {
            throw new IllegalStateException("Versioned Redis GEO generation is not ready");
        }
        applyVersionedToGeneration(after, aggregateVersion, generation);
    }

    private void applyVersionedToGeneration(Shop after, long aggregateVersion, String generation) {
        String shopId = after.getId().toString();
        boolean indexable = isIndexable(after);
        String typeId = indexable ? after.getTypeId().toString() : "";
        String targetTypeKey = indexable
                ? keyForGeneration(generation, after.getTypeId())
                : keyForGeneration(generation, null);
        Long applied = stringRedisTemplate.execute(
                VERSIONED_REFRESH_SCRIPT,
                List.of(
                        versionKey(generation),
                        typeKey(generation),
                        keyForGeneration(generation, null),
                        targetTypeKey),
                shopId,
                String.valueOf(Math.max(1L, aggregateVersion)),
                indexable ? "1" : "0",
                indexable ? String.valueOf(after.getX()) : "0",
                indexable ? String.valueOf(after.getY()) : "0",
                typeId,
                typeKeyPrefix(generation));
        if (applied == null) {
            throw new IllegalStateException("Redis GEO versioned update returned no result");
        }
    }

    private void catchUpAndPublishGeneration(
            String generation,
            int indexed,
            ShopProjectionLeaseService.Lease lease,
            long baseWatermark
    ) {
        long preCutoverWatermark = closeWatermark(lease);
        applyGeoChanges(generation, baseWatermark, preCutoverWatermark);
        lease.renew();
        lease.executeCutoverFenced(() -> {
            long finalWatermark = projectionOutboxRepository.highWatermark();
            applyGeoChanges(generation, preCutoverWatermark, finalWatermark);
            markGenerationReady(generation, indexed, lease.fencingToken());
            return finalWatermark;
        });
    }

    private long closeWatermark(ShopProjectionLeaseService.Lease lease) {
        return lease.executeCutoverFenced(projectionOutboxRepository::highWatermark);
    }

    private void applyGeoChanges(String generation, long exclusiveWatermark, long inclusiveWatermark) {
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
            Map<Long, Shop> shopsById = new HashMap<>();
            for (Shop shop : currentShops) {
                if (shop != null && shop.getId() != null) {
                    shopsById.put(shop.getId(), shop);
                }
            }
            if (shopsById.size() != requiredVersions.size()) {
                throw new IllegalStateException(
                        "Shop deletion projection is not implemented during GEO rebuild catch-up");
            }
            for (Map.Entry<Long, Long> required : requiredVersions.entrySet()) {
                Shop shop = shopsById.get(required.getKey());
                long currentVersion = Objects.requireNonNullElse(shop.getProjectionVersion(), 0L);
                if (currentVersion < required.getValue()) {
                    throw new IllegalStateException(
                            "Shop source version is behind rebuild watermark: " + shop.getId());
                }
                applyVersionedToGeneration(shop, currentVersion, generation);
            }
            long nextCursor = changes.get(changes.size() - 1).id();
            if (nextCursor <= cursor) {
                throw new IllegalStateException("Shop projection catch-up cursor did not advance");
            }
            cursor = nextCursor;
        }
    }

    public boolean isReady() {
        return readyGeneration() != null;
    }

    /**
     * A generation token is used instead of a boolean marker so readers can
     * detect a rebuild that starts and finishes during one GEO query.
     */
    public String readyGeneration() {
        if (locallyStale) {
            return null;
        }
        try {
            String generation = stringRedisTemplate.opsForValue().get(SHOP_GEO_INDEX_READY_KEY);
            return generation != null && (generation.startsWith("v1:") || generation.startsWith("v2:"))
                    ? generation
                    : null;
        } catch (RuntimeException error) {
            log.debug("Redis GEO readiness check failed: {}", error.getMessage());
            return null;
        }
    }

    private void deleteGeoDataKeys() {
        Set<String> keys = new HashSet<>();
        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(SHOP_GEO_KEY + "*")
                .count(500)
                .build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(scanOptions)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (!SHOP_GEO_INDEX_READY_KEY.equals(key) && !SHOP_GEO_INDEX_META_KEY.equals(key)) {
                    keys.add(key);
                }
            }
        }
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private void addToIndexes(Shop shop) {
        Point point = new Point(shop.getX(), shop.getY());
        String shopId = shop.getId().toString();
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_ALL_KEY, point, shopId);
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + shop.getTypeId(), point, shopId);
    }

    public String keyForGeneration(String generation, Long typeId) {
        if (generation != null && generation.startsWith("v2:")) {
            String suffix = typeId == null ? "all" : "type:" + typeId;
            return generationPrefix(generation) + suffix;
        }
        return typeId == null ? SHOP_GEO_ALL_KEY : SHOP_GEO_KEY + typeId;
    }

    private void addToGeneration(Shop shop, String generation) {
        Point point = new Point(shop.getX(), shop.getY());
        String shopId = shop.getId().toString();
        stringRedisTemplate.opsForGeo().add(keyForGeneration(generation, null), point, shopId);
        stringRedisTemplate.opsForGeo().add(keyForGeneration(generation, shop.getTypeId()), point, shopId);
    }

    private void writeGenerationState(Shop shop, String generation) {
        String shopId = shop.getId().toString();
        long version = Math.max(1L, Objects.requireNonNullElse(shop.getProjectionVersion(), 1L));
        stringRedisTemplate.opsForHash().put(versionKey(generation), shopId, String.valueOf(version));
        stringRedisTemplate.opsForHash().put(typeKey(generation), shopId, shop.getTypeId().toString());
    }

    private String versionKey(String generation) {
        return generationPrefix(generation) + "versions";
    }

    private String typeKey(String generation) {
        return generationPrefix(generation) + "types";
    }

    private String typeKeyPrefix(String generation) {
        return generationPrefix(generation) + "type:";
    }

    private String generationPrefix(String generation) {
        // A common hash tag keeps every key touched by the Lua update in one
        // Redis Cluster slot if this projection is moved to Cluster later.
        return SHOP_GEO_KEY + "{projection}:generation:" + generation + ":";
    }

    private void applyIncrementalRefresh(Shop before, Shop after) {
        Long id = after != null && after.getId() != null
                ? after.getId()
                : before == null ? null : before.getId();
        if (id == null) {
            return;
        }

        String shopId = id.toString();
        stringRedisTemplate.opsForGeo().remove(SHOP_GEO_ALL_KEY, shopId);
        removeFromTypeIndex(before, shopId);
        Long beforeTypeId = before == null ? null : before.getTypeId();
        Long afterTypeId = after == null ? null : after.getTypeId();
        if (!Objects.equals(beforeTypeId, afterTypeId)) {
            removeFromTypeIndex(after, shopId);
        }

        if (isIndexable(after)) {
            addToIndexes(after);
        }
    }

    private void removeFromTypeIndex(Shop shop, String shopId) {
        if (shop != null && shop.getTypeId() != null) {
            stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + shop.getTypeId(), shopId);
        }
    }

    private boolean isIndexable(Shop shop) {
        return shop != null
                && shop.getId() != null
                && shop.getTypeId() != null
                && isValidCoordinate(shop.getX(), shop.getY())
                && "verified".equals(shop.getGeoStatus());
    }

    private boolean isValidCoordinate(Double x, Double y) {
        return x != null
                && y != null
                && Double.isFinite(x)
                && Double.isFinite(y)
                && x >= -180D
                && x <= 180D
                && y >= -85.05112878D
                && y <= 85.05112878D;
    }

    private void markRebuilding() {
        stringRedisTemplate.delete(SHOP_GEO_INDEX_READY_KEY);
        stringRedisTemplate.delete(SHOP_GEO_INDEX_META_KEY);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "rebuilding");
        metadata.put("startedAt", Instant.now().toString());
        stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
    }

    private void markGenerationBuilding(String generation, long fencingToken) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "building");
        metadata.put("generation", generation);
        metadata.put("fencingToken", String.valueOf(fencingToken));
        metadata.put("startedAt", Instant.now().toString());
        stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
    }

    private void markGenerationReady(String generation, int indexed, long fencingToken) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "ready");
        metadata.put("indexed", String.valueOf(indexed));
        metadata.put("generation", generation);
        metadata.put("fencingToken", String.valueOf(fencingToken));
        metadata.put("completedAt", Instant.now().toString());
        stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
        stringRedisTemplate.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, generation);
    }

    private void markGenerationFailedSafely(String generation, RuntimeException failure) {
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("status", "failed");
            metadata.put("generation", generation);
            metadata.put("failedAt", Instant.now().toString());
            metadata.put("error", failure.getClass().getSimpleName());
            stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
        } catch (RuntimeException metadataFailure) {
            log.warn("Failed to record Redis GEO generation failure: {}", metadataFailure.getMessage());
        }
    }

    private void markReady(int indexed) {
        String generation = newGeneration();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "ready");
        metadata.put("indexed", String.valueOf(indexed));
        metadata.put("generation", generation);
        metadata.put("completedAt", Instant.now().toString());
        stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
        stringRedisTemplate.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, generation);
        locallyStale = false;
    }

    private void markIncrementalRefreshReady() {
        String generation = newGeneration();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "ready");
        metadata.put("generation", generation);
        metadata.put("refreshedAt", Instant.now().toString());
        stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
        stringRedisTemplate.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, generation);
        locallyStale = false;
    }

    private String newGeneration() {
        return "v1:" + UUID.randomUUID();
    }

    private void markFailedSafely(RuntimeException failure) {
        locallyStale = true;
        try {
            stringRedisTemplate.delete(SHOP_GEO_INDEX_READY_KEY);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("status", "failed");
            metadata.put("failedAt", Instant.now().toString());
            metadata.put("error", failure.getClass().getSimpleName());
            stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
        } catch (RuntimeException metadataFailure) {
            log.warn("Failed to record Redis GEO index rebuild failure: {}", metadataFailure.getMessage());
        }
    }

    private void markStaleSafely(RuntimeException failure) {
        locallyStale = true;
        try {
            stringRedisTemplate.delete(SHOP_GEO_INDEX_READY_KEY);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("status", "stale");
            metadata.put("staleAt", Instant.now().toString());
            metadata.put("error", failure.getClass().getSimpleName());
            stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
        } catch (RuntimeException metadataFailure) {
            log.warn("Failed to mark Redis GEO index stale: {}", metadataFailure.getMessage());
        }
    }
}
