package com.citycopilot.core.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.projection.ShopProjectionOutboxRepository;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.IShopTypeService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.citycopilot.core.service.discovery.ShopSearchProjectionService;
import com.citycopilot.core.utils.CacheClient;
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
import java.util.concurrent.TimeUnit;

import static com.citycopilot.core.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    CacheClient cacheClient;

    @Resource
    private GeoProperties geoProperties;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private ShopOwnershipPolicy shopOwnershipPolicy;

    @Resource
    private ShopGeoIndexService shopGeoIndexService;

    @Resource
    private ShopSearchProjectionService shopSearchProjectionService;

    @Resource
    private ShopProjectionOutboxRepository shopProjectionOutboxRepository;

    @Resource
    private ShopProjectionProperties shopProjectionProperties;

    @Override
    public Result queryShopById(Long id) {
        //日常使用：缓存穿透方案
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //特殊活动期间：逻辑过期方案（需提前预热数据）
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("SHOP_NOT_FOUND", "店铺获取失败！");
        }
        return Result.ok(shop);
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
    public Result update(Shop shop) {// 更新数据库
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
            if (!isValidCoordinate(effectiveX, effectiveY)) {
                return Result.fail("SHOP_GEO_INVALID", "已核验店铺必须提供合法坐标");
            }
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        Shop after = getById(id);
        recordShopChanged(before, after);
        operationLogService.record("shop", "shop_update", "shop", id, String.valueOf(id), before, after, true, null);
        return Result.ok();
    }

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
        if (shop.getTypeId() != null && shopTypeService.getById(shop.getTypeId()) == null) {
            return Result.fail("SHOP_TYPE_NOT_FOUND", "店铺分类不存在");
        }
        if ((shop.getX() == null) != (shop.getY() == null)) {
            return Result.fail("SHOP_GEO_INVALID", "经纬度必须同时填写");
        }
        if (shop.getX() != null && !isValidCoordinate(shop.getX(), shop.getY())) {
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
                && !isValidCoordinate(shop.getX(), shop.getY())) {
            return Result.fail("SHOP_GEO_INVALID", "已核验店铺必须提供合法坐标");
        }
        return Result.ok();
    }

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
            shop.setGeoStatus(isValidCoordinate(shop.getX(), shop.getY()) ? "pending" : "failed");
        }
        if (StrUtil.isBlank(shop.getGeoSource()) && isValidCoordinate(shop.getX(), shop.getY())) {
            shop.setGeoSource("manual");
        }
        if (isValidCoordinate(shop.getX(), shop.getY()) && shop.getGeoUpdatedAt() == null) {
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
        if (!isValidCoordinate(request.getX(), request.getY())) {
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
        return Result.ok(page);
    }

    void refreshShopIndexesAfterCommit(Shop before, Shop after) {
        Shop beforeSnapshot = geoIndexSnapshot(before);
        Shop afterSnapshot = geoIndexSnapshot(after);
        Long searchShopId = after == null ? null : after.getId();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
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

    private void recordShopChanged(Shop before, Shop after) {
        if (shopProjectionProperties != null && shopProjectionProperties.isOutboxEnabled()) {
            shopProjectionOutboxRepository.append(before, after);
            return;
        }
        // Unit tests and explicitly opted-in legacy deployments retain the
        // previous behavior. Production profiles keep this escape hatch off.
        if (shopProjectionProperties == null || shopProjectionProperties.isLegacyAfterCommitEnabled()) {
            refreshShopIndexesAfterCommit(before, after);
        }
    }

    void refreshShopGeoIndexAfterCommit(Shop before, Shop after) {
        refreshShopIndexesAfterCommit(before, after);
    }

    private void refreshShopIndexesSafely(Shop before, Shop after, Long searchShopId) {
        refreshShopGeoIndexSafely(before, after);
        refreshShopSearchProjectionSafely(searchShopId);
    }

    private void refreshShopGeoIndexSafely(Shop before, Shop after) {
        try {
            shopGeoIndexService.refreshShopGeoIndex(before, after);
        } catch (RuntimeException error) {
            Long shopId = after != null ? after.getId() : before == null ? null : before.getId();
            log.warn("Shop {} committed but Redis GEO index refresh failed: {}", shopId, error.getMessage());
        }
    }

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
