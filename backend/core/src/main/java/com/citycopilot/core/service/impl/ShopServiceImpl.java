package com.citycopilot.core.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.dto.ShopViewDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.projection.ShopProjectionOutboxRepository;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.IShopTypeService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.citycopilot.core.service.discovery.ShopSearchProjectionService;
import com.citycopilot.core.utils.CacheClient;
import com.citycopilot.core.utils.RedisGeoCoordinates;
import com.citycopilot.core.utils.SystemConstants;
import com.citycopilot.core.utils.UserHolder;
import com.citycopilot.core.security.ShopOwnershipPolicy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import com.citycopilot.contract.booking.BookingContracts.ShopView;
import java.util.concurrent.TimeUnit;

import static com.citycopilot.core.utils.RedisConstants.*;

/**
 * 店铺主数据服务实现，协调 MySQL、详情缓存、Redis GEO 和搜索投影。
 *
 * <p>数据库是店铺事实源。写入成功后通过投影 Outbox 推进派生索引；兼容模式下则在事务提交后直接刷新。</p>
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /** Redis 客户端，用于删除发生变更的店铺详情缓存。 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** Cache Aside 工具，提供空值缓存、互斥重建和 TTL 抖动。 */
    @Resource
    CacheClient cacheClient;

    /** GEO 查询配置，决定店铺列表允许的分页大小。 */
    @Resource
    private GeoProperties geoProperties;

    /** 业务审计服务，记录店铺与坐标写操作的前后快照。 */
    @Resource
    private OperationLogService operationLogService;

    /** 店铺分类服务，用于验证写入的分类主键。 */
    @Resource
    private IShopTypeService shopTypeService;

    /** 店铺所有权策略，统一判断普通用户和管理员的写权限。 */
    @Resource
    private ShopOwnershipPolicy shopOwnershipPolicy;

    /** Redis GEO 投影服务，提供附近店铺索引。 */
    @Resource
    private ShopGeoIndexService shopGeoIndexService;

    /** 全文搜索投影服务，维护搜索引擎中的店铺文档。 */
    @Resource
    private ShopSearchProjectionService shopSearchProjectionService;

    /** 店铺投影 Outbox 仓储，在业务事务内记录待投影变更。 */
    @Resource
    private ShopProjectionOutboxRepository shopProjectionOutboxRepository;

    /** 投影模式配置，控制可靠 Outbox 与旧提交后刷新路径。 */
    @Resource
    private ShopProjectionProperties shopProjectionProperties;

    @Override
    public ShopViewDTO findAuthoritativeShop(Long shopId) {
        Shop shop = getById(shopId);
        return shop == null ? null : view(shop);
    }

    @Override
    public List<ShopView> listBookingShopsForCurrentUser() {
        var user = UserHolder.getUser();
        return query().select("id", "name")
                .eq(!"admin".equals(user.getRole()), "owner_user_id", user.getId())
                .orderByAsc("id").list().stream()
                .map(shop -> new ShopView(shop.getId().toString(), shop.getName())).toList();
    }

    @Override
    public long countShops() {
        return query().count();
    }

    @Override
    public long countShopsMissingGeo() {
        return query().and(wrapper -> wrapper.isNull("x").or().isNull("y")).count();
    }

    @Override
    public long countShopsByGeoStatus(String status) {
        return query().eq("geo_status", status).count();
    }

    @Override
    public Result queryShopById(Long id) {
        // 互斥重建避免热点缓存失效时大量请求同时回源数据库。
        Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                CACHE_SHOP_TTL_JITTER,
                TimeUnit.MINUTES
        );
        if (shop == null) {
            return Result.fail("SHOP_NOT_FOUND", "店铺获取失败！");
        }
        return Result.ok(view(shop));
    }

    @Override
    @Transactional
    public Result createShop(Shop shop) {
        Result validation = validateShopPayload(shop, true);
        if (!Boolean.TRUE.equals(validation.getSuccess())) {
            operationLogService.record("shop", "shop_create", "shop", null, null, null, shop, false, validation.getErrorMsg());
            return validation;
        }
        shopOwnershipPolicy.assignOwnerOnCreate(shop, UserHolder.getUser());
        applyShopDefaults(shop);
        save(shop);
        Shop after = getById(shop.getId());
        recordShopChanged(null, after);
        operationLogService.record("shop", "shop_create", "shop", shop.getId(), String.valueOf(shop.getId()), null, after, true, null);
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            operationLogService.record("shop", "shop_update", "shop", null, null, null, shop, false, "店铺id不能为空");
            return Result.fail("BAD_REQUEST", "店铺id不能为空");
        }
        Result validation = validateShopPayload(shop, false);
        if (!Boolean.TRUE.equals(validation.getSuccess())) {
            operationLogService.record("shop", "shop_update", "shop", id, String.valueOf(id), null, shop, false, validation.getErrorMsg());
            return validation;
        }
        Shop before = getById(id);
        if (before == null) {
            operationLogService.record("shop", "shop_update", "shop", id, String.valueOf(id), null, shop, false, "店铺不存在");
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }
        shopOwnershipPolicy.assertWritable(before, UserHolder.getUser());
        if ("verified".equals(shop.getGeoStatus())) {
            Double effectiveX = shop.getX() == null ? before.getX() : shop.getX();
            Double effectiveY = shop.getY() == null ? before.getY() : shop.getY();
            if (!RedisGeoCoordinates.isValid(effectiveX, effectiveY)) {
                return Result.fail("SHOP_GEO_INVALID", "已核验店铺必须提供合法坐标");
            }
        }
        updateById(shop);
        // Cache Aside 写路径：提交新事实后删除旧缓存，让下一次读取按需重建。
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        Shop after = getById(id);
        recordShopChanged(before, after);
        operationLogService.record("shop", "shop_update", "shop", id, String.valueOf(id), before, after, true, null);
        return Result.ok();
    }

    /**
     * 校验店铺写请求中影响数据完整性和检索投影的字段。
     *
     * @param shop 待创建或更新的店铺
     * @param creating {@code true} 表示创建场景，必填字段更严格
     * @return 校验成功或稳定业务错误
     */
    private Result validateShopPayload(Shop shop, boolean creating) {
        if (shop == null) {
            return Result.fail("BAD_REQUEST", "店铺不能为空");
        }
        if (StrUtil.isBlank(shop.getName())) {
            return Result.fail("BAD_REQUEST", "店铺名称不能为空");
        }
        if (creating && shop.getTypeId() == null) {
            return Result.fail("BAD_REQUEST", "店铺分类不能为空");
        }
        if (shop.getTypeId() != null && shopTypeService.findShopType(shop.getTypeId()) == null) {
            return Result.fail("SHOP_TYPE_NOT_FOUND", "店铺分类不存在");
        }
        if ((shop.getX() == null) != (shop.getY() == null)) {
            return Result.fail("SHOP_GEO_INVALID", "经纬度必须同时填写");
        }
        if (shop.getX() != null && !RedisGeoCoordinates.isValid(shop.getX(), shop.getY())) {
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }
        if (shop.getAvgPrice() != null && shop.getAvgPrice() < 0) {
            return Result.fail("BAD_REQUEST", "人均价格不合法");
        }
        if (shop.getScore() != null && (shop.getScore() < 0 || shop.getScore() > 50)) {
            return Result.fail("BAD_REQUEST", "评分不合法");
        }
        if (StrUtil.isNotBlank(shop.getGeoStatus()) && !isValidGeoStatus(shop.getGeoStatus())) {
            return Result.fail("BAD_REQUEST", "坐标状态不合法");
        }
        if (creating
                && "verified".equals(shop.getGeoStatus())
                && !RedisGeoCoordinates.isValid(shop.getX(), shop.getY())) {
            return Result.fail("SHOP_GEO_INVALID", "已核验店铺必须提供合法坐标");
        }
        return Result.ok();
    }

    /**
     * 为新店铺补齐统计值和地理状态默认值。
     *
     * @param shop 即将持久化的新店铺
     */
    private void applyShopDefaults(Shop shop) {
        if (shop.getSold() == null) {
            shop.setSold(0);
        }
        if (shop.getComments() == null) {
            shop.setComments(0);
        }
        if (shop.getScore() == null) {
            shop.setScore(0);
        }
        if (StrUtil.isBlank(shop.getGeoStatus())) {
            shop.setGeoStatus(RedisGeoCoordinates.isValid(shop.getX(), shop.getY()) ? "pending" : "failed");
        }
        if (StrUtil.isBlank(shop.getGeoSource()) && RedisGeoCoordinates.isValid(shop.getX(), shop.getY())) {
            shop.setGeoSource("manual");
        }
        if (RedisGeoCoordinates.isValid(shop.getX(), shop.getY()) && shop.getGeoUpdatedAt() == null) {
            shop.setGeoUpdatedAt(LocalDateTime.now());
        }
    }

    @Override
    @Transactional
    public Result updateShopGeo(ShopGeoUpdateDTO request) {
        if (request == null || request.getId() == null) {
            operationLogService.record("geo", "shop_geo_update", "shop", null, null, null, request, false, "店铺id不能为空");
            return Result.fail("BAD_REQUEST", "店铺id不能为空");
        }
        if (!RedisGeoCoordinates.isValid(request.getX(), request.getY())) {
            operationLogService.record("geo", "shop_geo_update", "shop", request.getId(), String.valueOf(request.getId()), null, request, false, "坐标参数不合法");
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }
        String geoStatus = StrUtil.blankToDefault(request.getGeoStatus(), "verified");
        if (!isValidGeoStatus(geoStatus)) {
            operationLogService.record("geo", "shop_geo_update", "shop", request.getId(),
                    String.valueOf(request.getId()), null, request, false, "坐标状态不合法");
            return Result.fail("BAD_REQUEST", "坐标状态不合法");
        }

        Shop before = getById(request.getId());
        if (before == null) {
            operationLogService.record("geo", "shop_geo_update", "shop", request.getId(), String.valueOf(request.getId()), null, request, false, "店铺不存在");
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }
        shopOwnershipPolicy.assertWritable(before, UserHolder.getUser());

        Shop update = new Shop()
                .setId(request.getId())
                .setX(request.getX())
                .setY(request.getY())
                .setPoiId(request.getPoiId())
                .setAdcode(request.getAdcode())
                .setGeoSource(StrUtil.blankToDefault(request.getGeoSource(), "amap"))
                .setGeoStatus(geoStatus)
                .setGeoUpdatedAt(LocalDateTime.now());

        updateById(update);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + request.getId());

        Shop after = getById(request.getId());
        recordShopChanged(before, after);
        operationLogService.record("geo", "shop_geo_update", "shop", request.getId(), String.valueOf(request.getId()), before, after, true, null);
        return Result.ok(after);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Integer size) {
        int pageSize = shopPageSize(size);
        Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, pageSize));
        return Result.ok(page.convert(this::view));
    }

    @Override
    public Result queryShopByName(String name, Integer current, Integer size) {
        Page<Shop> page = query().like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, shopPageSize(size)));
        return Result.ok(page.getRecords().stream().map(this::view).toList());
    }

    private ShopViewDTO view(Shop shop) {
        return new ShopViewDTO(shop.getId(), shop.getName(), shop.getTypeId(), shop.getOwnerUserId(),
                shop.getImages(), shop.getArea(), shop.getAddress(), shop.getX(), shop.getY(),
                shop.getPoiId(), shop.getAdcode(), shop.getGeoSource(), shop.getGeoStatus(),
                shop.getGeoUpdatedAt(), shop.getAvgPrice(), shop.getSold(), shop.getComments(),
                shop.getScore(), shop.getOpenHours(), shop.getCreateTime(), shop.getUpdateTime(),
                shop.getProjectionVersion(), shop.getDistance());
    }

    /**
     * 在当前事务成功提交后刷新派生索引。
     *
     * <p>刷新不能早于提交，否则其他线程可能从索引读到数据库尚不可见的数据。</p>
     *
     * @param before 变更前的地理快照，删除旧 GEO 成员时使用
     * @param after 变更后的地理快照，写入新 GEO 成员时使用
     */
    void refreshShopIndexesAfterCommit(Shop before, Shop after) {
        Shop beforeSnapshot = geoIndexSnapshot(before);
        Shop afterSnapshot = geoIndexSnapshot(after);
        Long searchShopId = after == null ? null : after.getId();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    /** 数据库提交成功后再发布到 Redis GEO 和搜索投影。 */
                    @Override
                    public void afterCommit() {
                        refreshShopIndexesSafely(beforeSnapshot, afterSnapshot, searchShopId);
                    }
                });
            } else {
                Long shopId = afterSnapshot != null
                        ? afterSnapshot.getId()
                        : beforeSnapshot == null ? null : beforeSnapshot.getId();
                if (shopSearchProjectionService != null) {
                    shopSearchProjectionService.markReconciliationRequired();
                }
                log.warn("Shop {} index refresh skipped because transaction synchronization is unavailable", shopId);
            }
            return;
        }
        refreshShopIndexesSafely(beforeSnapshot, afterSnapshot, searchShopId);
    }

    /**
     * 记录一次店铺事实变更，并按配置选择可靠投影路径。
     *
     * @param before 变更前店铺；创建时为 {@code null}
     * @param after 变更后店铺；当前创建和更新路径必须提供
     */
    private void recordShopChanged(Shop before, Shop after) {
        if (shopProjectionProperties != null && shopProjectionProperties.isOutboxEnabled()) {
            // Outbox 与店铺写入共用事务，保证“事实已变更”必然对应一条可重试投影事件。
            shopProjectionOutboxRepository.append(before, after);
            return;
        }
        // 单元测试和显式启用的旧部署保留提交后直刷路径；生产配置默认关闭该逃生口。
        if (shopProjectionProperties == null || shopProjectionProperties.isLegacyAfterCommitEnabled()) {
            refreshShopIndexesAfterCommit(before, after);
        }
    }

    /**
     * 兼容旧调用点的 GEO 刷新入口，实际同时维护 GEO 与搜索投影。
     *
     * @param before 变更前快照
     * @param after 变更后快照
     */
    void refreshShopGeoIndexAfterCommit(Shop before, Shop after) {
        refreshShopIndexesAfterCommit(before, after);
    }

    /**
     * 顺序刷新两个派生索引；单个索引失败不会回滚已提交的店铺事实。
     *
     * @param before 变更前地理快照
     * @param after 变更后地理快照
     * @param searchShopId 需要重建搜索文档的店铺主键
     */
    private void refreshShopIndexesSafely(Shop before, Shop after, Long searchShopId) {
        refreshShopGeoIndexSafely(before, after);
        refreshShopSearchProjectionSafely(searchShopId);
    }

    /**
     * 尝试刷新 Redis GEO；失败只记录告警，后续由对账任务修复。
     *
     * @param before 变更前地理快照
     * @param after 变更后地理快照
     */
    private void refreshShopGeoIndexSafely(Shop before, Shop after) {
        try {
            shopGeoIndexService.refreshShopGeoIndex(before, after);
        } catch (RuntimeException error) {
            Long shopId = after != null ? after.getId() : before == null ? null : before.getId();
            log.warn("Shop {} committed but Redis GEO index refresh failed: {}", shopId, error.getMessage());
        }
    }

    /**
     * 尝试按事实源重建单个搜索文档，失败时标记全量对账需求。
     *
     * @param shopId 店铺主键
     */
    private void refreshShopSearchProjectionSafely(Long shopId) {
        if (shopSearchProjectionService == null || shopId == null) {
            return;
        }
        try {
            shopSearchProjectionService.upsertLatestBestEffort(shopId);
        } catch (RuntimeException error) {
            shopSearchProjectionService.markReconciliationRequired();
            log.warn("Shop {} committed but search projection refresh failed: {}", shopId, error.getMessage());
        }
    }

    /**
     * 提取事务外刷新所需的最小不可变地理快照，避免后续对象被继续修改。
     *
     * @param shop 原始店铺对象
     * @return 仅含主键、分类、坐标和核验状态的副本
     */
    private Shop geoIndexSnapshot(Shop shop) {
        if (shop == null) {
            return null;
        }
        return new Shop()
                .setId(shop.getId())
                .setTypeId(shop.getTypeId())
                .setX(shop.getX())
                .setY(shop.getY())
                .setGeoStatus(shop.getGeoStatus());
    }

    private boolean isValidGeoStatus(String status) {
        return "pending".equals(status) || "verified".equals(status) || "failed".equals(status);
    }

    private int shopPageSize(Integer requestedSize) {
        return geoProperties == null
                ? SystemConstants.DEFAULT_PAGE_SIZE
                : geoProperties.resolveShopPageSize(requestedSize);
    }

    @Override
    public void assertWritableByCurrentUser(Long shopId) {
        Shop shop = getById(shopId);
        if (shop == null) {
            throw new IllegalArgumentException("店铺不存在");
        }
        shopOwnershipPolicy.assertWritable(shop, UserHolder.getUser());
    }
}
