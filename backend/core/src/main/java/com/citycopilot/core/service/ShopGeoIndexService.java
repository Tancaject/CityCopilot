package com.citycopilot.core.service;

import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
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

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
            return generation != null && generation.startsWith("v1:") ? generation : null;
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
