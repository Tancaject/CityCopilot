package com.citycopilot.core.service;

import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.projection.ShopProjectionLeaseService;
import com.citycopilot.core.projection.ShopProjectionOutboxRepository;
import com.citycopilot.core.projection.ShopProjectionRetirementRegistry;
import com.citycopilot.core.projection.ProjectionGenerationCleanupResult;
import com.citycopilot.core.utils.RedisGeoCoordinates;
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
import java.util.ArrayList;
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

/**
 * 店铺 Redis GEO 投影生命周期服务。
 *
 * <p>负责全量代际重建、版本化增量更新、Outbox 水位追平和就绪发布；MySQL 店铺表始终是事实源。</p>
 */
@Slf4j
@Service
public class ShopGeoIndexService {

    /**
     * 版本化 GEO 原子更新脚本：拒绝旧版本，删除旧分类成员，写入新坐标并记录源版本。
     *
     * <p>所有键共享 Redis Cluster hash tag，因此未来迁移 Cluster 后仍能在单槽执行。</p>
     */
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

    /** 店铺事实表 Mapper，用于全量构建和增量追平。 */
    @Resource
    private ShopMapper shopMapper;

    /** Redis GEO、版本 Hash、类型 Hash 和代际元数据客户端。 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 可选共享数据库租约，防止多副本并发发布代际。 */
    @Autowired(required = false)
    private ShopProjectionLeaseService projectionLeaseService;

    /** 可选投影租约 TTL 和角色配置。 */
    @Autowired(required = false)
    private ShopProjectionProperties projectionProperties;

    /** 可选投影 Outbox，用于重建期间追平并发店铺变更。 */
    @Autowired(required = false)
    private ShopProjectionOutboxRepository projectionOutboxRepository;

    @Autowired(required = false)
    private ShopProjectionRetirementRegistry retirementRegistry;

    /**
     * 当前进程观察到的本地陈旧标记。
     *
     * <p>增量更新因 Redis 故障失败后，即使远端仍残留旧 ready 标记，本实例也不会继续信任它。</p>
     */
    private volatile boolean locallyStale;

    /**
     * 全量重建店铺 GEO 索引。
     *
     * <p>有共享租约时构建隔离 v2 代际；兼容模式使用本地同步锁重建 v1 索引。</p>
     *
     * @return 成功写入索引的已核验店铺数量
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

    /**
     * 兼容单写者部署的原地 v1 索引重建。
     *
     * @return 写入店铺数
     */
    private int syncLegacyIndex() {
        locallyStale = true;
        try {
            markRebuilding();
            long highWatermark = shopMapper.projectionHighWatermark();
            deleteGeoDataKeys();
            int indexed = writeBasePages(null, null, highWatermark);
            markReady(indexed);
            log.info("Synced {} shops into Redis GEO indexes", indexed);
            return indexed;
        } catch (RuntimeException error) {
            markFailedSafely(error);
            throw error;
        }
    }

    /**
     * 在隔离键空间中构建 v2 代际，并在重新校验数据库 fence 后发布。
     *
     * <p>整个构建期间读请求仍停留在旧代际，避免看到半成品索引。</p>
     *
     * @param lease 当前投影写租约
     * @return 新代际写入店铺数
     */
    private int syncGeneration(ShopProjectionLeaseService.Lease lease) {
        locallyStale = true;
        String generation = "v2:" + lease.fencingToken() + ":" + UUID.randomUUID();
        try {
            long baseWatermark = projectionOutboxRepository == null
                    ? 0L
                    : closeWatermark(lease);
            long highWatermark = shopMapper.projectionHighWatermark();
            markGenerationBuilding(generation, lease.fencingToken());
            int indexed = writeBasePages(generation, lease, highWatermark);
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

    private int writeBasePages(String generation, ShopProjectionLeaseService.Lease lease, long highWatermark) {
        long cursor = 0L;
        int indexed = 0;
        while (cursor < highWatermark) {
            if (lease != null) lease.renew();
            List<Shop> shops = shopMapper.projectionPage(cursor, highWatermark);
            if (shops.isEmpty()) break;
            indexed += addBasePage(shops, generation);
            cursor = shops.get(shops.size() - 1).getId();
        }
        return indexed;
    }

    /**
     * 将一次已提交的 MySQL 店铺变更应用到兼容 v1 GEO 索引。
     *
     * @param before 变更前地理快照
     * @param after 变更后地理快照
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
     * Projector 专用的 v2 版本化增量更新。
     *
     * <p>调用方持有共享写租约；Redis 记录源版本，使至少一次投递可幂等重试。</p>
     *
     * @param after 最新店铺事实
     * @param aggregateVersion Outbox 聚合版本
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

    /**
     * 调用 Lua 将一条店铺事实原子应用到指定代际。
     *
     * @param after 最新店铺事实
     * @param aggregateVersion 源投影版本
     * @param generation 目标 v2 代际
     */
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

    /**
     * 追平全量扫描后的 Outbox 增量，并在切换屏障内发布最终代际。
     *
     * @param generation 待发布代际
     * @param indexed 全量扫描写入数量
     * @param lease 当前写租约
     * @param baseWatermark 全量扫描开始前的封闭水位
     */
    private void catchUpAndPublishGeneration(
            String generation,
            int indexed,
            ShopProjectionLeaseService.Lease lease,
            long baseWatermark
    ) {
        long preCutoverWatermark = closeWatermark(lease);
        applyGeoChanges(generation, lease, baseWatermark, preCutoverWatermark);
        lease.renew();
        lease.executeCutoverFenced(() -> {
            long finalWatermark = projectionOutboxRepository.highWatermark();
            applyGeoChanges(generation, lease, preCutoverWatermark, finalWatermark);
            markGenerationReady(generation, indexed, lease.fencingToken());
            return finalWatermark;
        });
    }

    private long closeWatermark(ShopProjectionLeaseService.Lease lease) {
        return lease.executeCutoverFenced(projectionOutboxRepository::highWatermark);
    }

    /**
     * 按批把指定 Outbox 水位区间的最新店铺版本写入新 GEO 代际。
     *
     * @param generation 目标代际
     * @param lease 当前写租约，每页续租后才继续追平
     * @param exclusiveWatermark 不包含的起始事件主键
     * @param inclusiveWatermark 包含的结束事件主键
     */
    private void applyGeoChanges(String generation, ShopProjectionLeaseService.Lease lease,
                                 long exclusiveWatermark, long inclusiveWatermark) {
        long cursor = exclusiveWatermark;
        while (cursor < inclusiveWatermark) {
            lease.renew();
            List<ShopProjectionOutboxRepository.ProjectionChange> changes =
                    projectionOutboxRepository.changesAfter(cursor, inclusiveWatermark, 500);
            if (changes.isEmpty()) {
                return;
            }
            Map<Long, Long> requiredVersions = new LinkedHashMap<>();
            // 同一店铺多次变更只需按最高聚合版本补入当前事实。
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

    /** @return 当前是否存在本实例可安全读取的已发布代际 */
    public boolean isReady() {
        return readyGeneration() != null;
    }

    /**
     * 读取当前已发布代际 token。
     *
     * <p>使用代际而非布尔标记，使查询可发现执行期间发生的重建切换并整批降级。</p>
     *
     * @return v1/v2 代际；未就绪或本地已知陈旧时为空
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

    /** 使用 SCAN 删除旧版分类 GEO 数据键，避免 KEYS 阻塞 Redis。 */
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
                    if (keys.size() == 500) {
                        stringRedisTemplate.delete(keys);
                        keys.clear();
                    }
                }
            }
        }
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /**
     * 把店铺写入旧版全分类和分类 GEO 索引。
     *
     * @param shop 已通过索引资格校验的店铺
     */
    private void addToIndexes(Shop shop) {
        Point point = new Point(shop.getX(), shop.getY());
        String shopId = shop.getId().toString();
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_ALL_KEY, point, shopId);
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + shop.getTypeId(), point, shopId);
    }

    /**
     * 解析给定代际和分类对应的 Redis GEO 键。
     *
     * @param generation 当前代际
     * @param typeId 可选分类主键；为空表示全分类
     * @return v2 隔离键或兼容 v1 键
     */
    public String keyForGeneration(String generation, Long typeId) {
        if (generation != null && generation.startsWith("v2:")) {
            String suffix = typeId == null ? "all" : "type:" + typeId;
            return generationPrefix(generation) + suffix;
        }
        return typeId == null ? SHOP_GEO_ALL_KEY : SHOP_GEO_KEY + typeId;
    }

    /** 未发布的基础代际按页写 GEO 和版本 Hash；任何一批失败都不发布 ready 指针。 */
    private int addBasePage(List<Shop> shops, String generation) {
        Map<String, Point> points = new LinkedHashMap<>();
        Map<Long, Map<String, Point>> byType = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (Shop shop : shops) {
            if (!isIndexable(shop)) continue;
            String shopId = shop.getId().toString();
            Point point = new Point(shop.getX(), shop.getY());
            points.put(shopId, point);
            byType.computeIfAbsent(shop.getTypeId(), ignored -> new LinkedHashMap<>()).put(shopId, point);
            if (generation != null) {
                long version = Math.max(1L, Objects.requireNonNullElse(shop.getProjectionVersion(), 1L));
                versions.put(shopId, String.valueOf(version));
                types.put(shopId, shop.getTypeId().toString());
            }
        }
        if (points.isEmpty()) return 0;
        stringRedisTemplate.opsForGeo().add(keyForGeneration(generation, null), points);
        byType.forEach((typeId, members) -> stringRedisTemplate.opsForGeo().add(keyForGeneration(generation, typeId), members));
        if (generation != null) {
            stringRedisTemplate.opsForHash().putAll(versionKey(generation), versions);
            stringRedisTemplate.opsForHash().putAll(typeKey(generation), types);
        }
        return points.size();
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
        // 统一 hash tag 使 Lua 涉及的版本、类型与 GEO 键落在同一 Redis Cluster 槽。
        return SHOP_GEO_KEY + "{projection}:generation:" + generation + ":";
    }

    /** 仅处理登记过的 v2 退役代际；每批 UNLINK 前在 writer fence 内重新检查指针与构建元数据。 */
    public List<ProjectionGenerationCleanupResult> cleanupRetiredGenerations(ShopProjectionLeaseService.Lease lease,
                                        ShopProjectionProperties.Cleanup cleanup) {
        if (retirementRegistry == null) throw new IllegalStateException("GEO retirement registry is unavailable");
        List<ProjectionGenerationCleanupResult> results = new ArrayList<>();
        for (String generation : retirementRegistry.due("geo", cleanup.getRetention(), cleanup.getGenerationBatchSize())) {
            if (!isManagedGeneration(generation)) {
                throw new IllegalStateException("Refuse to delete malformed retired GEO generation: " + generation);
            }
            lease.renew();
            lease.executeFenced(() -> {
                String active = stringRedisTemplate.opsForValue().get(SHOP_GEO_INDEX_READY_KEY);
                Object building = stringRedisTemplate.opsForHash().get(SHOP_GEO_INDEX_META_KEY, "generation");
                String retained = !isManagedGeneration(active) ? "RETAINED_UNREADY"
                        : generation.equals(active) ? "RETAINED_ACTIVE"
                        : generation.equals(building) ? "RETAINED_METADATA_REFERENCE" : null;
                if (retained != null) {
                    results.add(new ProjectionGenerationCleanupResult("geo", generation, retained, 0));
                    return;
                }
                Set<String> keys = new HashSet<>();
                boolean complete = true;
                ScanOptions options = ScanOptions.scanOptions().match(generationPrefix(generation) + "*")
                        .count(cleanup.getKeyBatchSize()).build();
                try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(cursor.next());
                        if (keys.size() >= cleanup.getKeyBatchSize()) {
                            complete = false;
                            break;
                        }
                    }
                }
                long unlinked = keys.isEmpty() ? 0L : Objects.requireNonNull(stringRedisTemplate.unlink(keys),
                        "Redis did not return the number of unlinked generation keys");
                log.info("Retired GEO generation cleanup: generation={}, unlinkedKeys={}, scanCompleted={}",
                        generation, unlinked, complete);
                if (complete) {
                    retirementRegistry.forget("geo", generation);
                }
                results.add(new ProjectionGenerationCleanupResult("geo", generation,
                        complete ? "REMOVED_OR_ABSENT" : "PARTIAL_RETRY_REQUIRED", unlinked));
            });
        }
        return List.copyOf(results);
    }

    private boolean isManagedGeneration(String generation) {
        return generation != null && generation.matches("v2:[0-9]+:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /**
     * 从旧版 GEO 索引删除变更前成员，再按变更后资格重新写入。
     *
     * @param before 变更前快照
     * @param after 变更后快照
     */
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

    /**
     * 判断店铺是否可进入附近查询索引。
     *
     * @param shop 店铺事实
     * @return 主键、分类、坐标完整且地理状态已核验时返回 {@code true}
     */
    private boolean isIndexable(Shop shop) {
        return shop != null
                && shop.getId() != null
                && shop.getTypeId() != null
                && RedisGeoCoordinates.isValid(shop.getX(), shop.getY())
                && "verified".equals(shop.getGeoStatus());
    }

    /** 清除旧版 ready 标记并记录重建开始元数据。 */
    private void markRebuilding() {
        stringRedisTemplate.delete(SHOP_GEO_INDEX_READY_KEY);
        stringRedisTemplate.delete(SHOP_GEO_INDEX_META_KEY);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "rebuilding");
        metadata.put("startedAt", Instant.now().toString());
        stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
    }

    /**
     * 记录隔离代际正在构建。
     *
     * @param generation 新代际
     * @param fencingToken 当前写租约 token
     */
    private void markGenerationBuilding(String generation, long fencingToken) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "building");
        metadata.put("generation", generation);
        metadata.put("fencingToken", String.valueOf(fencingToken));
        metadata.put("startedAt", Instant.now().toString());
        stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
    }

    /**
     * 先写完整元数据，再发布 ready 代际指针。
     *
     * @param generation 新代际
     * @param indexed 写入数量
     * @param fencingToken 当前写租约 token
     */
    private void markGenerationReady(String generation, int indexed, long fencingToken) {
        String previous = stringRedisTemplate.opsForValue().get(SHOP_GEO_INDEX_READY_KEY);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "ready");
        metadata.put("indexed", String.valueOf(indexed));
        metadata.put("generation", generation);
        metadata.put("fencingToken", String.valueOf(fencingToken));
        metadata.put("completedAt", Instant.now().toString());
        stringRedisTemplate.opsForHash().putAll(SHOP_GEO_INDEX_META_KEY, metadata);
        stringRedisTemplate.opsForValue().set(SHOP_GEO_INDEX_READY_KEY, generation);
        if (retirementRegistry != null && isManagedGeneration(previous) && !previous.equals(generation)) {
            retirementRegistry.recordAfterPublication("geo", previous);
        }
    }

    /**
     * 尽力记录隔离代际构建失败，不掩盖原始异常。
     *
     * @param generation 失败代际
     * @param failure 原始失败
     */
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
