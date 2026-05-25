package com.citycopilot.core.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.IShopTypeService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.utils.CacheClient;
import com.citycopilot.core.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.citycopilot.core.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
        applyShopDefaults(shop);
        save(shop);
        Shop after = getById(shop.getId());
        refreshShopGeoIndex(null, after);
        operationLogService.record("shop", "shop_create", "shop", shop.getId(), String.valueOf(shop.getId()), null, after, true, null);
        return Result.ok(shop.getId());
    }

    /*public Shop queryWithMutex(Long id){//互斥锁解决缓存击穿
        String key=CACHE_SHOP_KEY + id;
        // 通过redis查商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断redis中是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在则直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值“”
        if (shopJson!=null){
            //返回错误信息
            return null;
        }
        //获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean isLock=tryLock(lockKey);
            //判断是否获取成功
            if (!isLock){
                //失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //成功，先二次检查缓存（Double-Check），避免重复查询数据库
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            //缓存仍不存在，查数据库
            shop = getById(id);
            // 数据库中也不存在，则返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            // 存在，写入redis并设置超时时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL,
                    TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }*/


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
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        Shop after = getById(id);
        refreshShopGeoIndex(before, after);
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

        Shop before = getById(request.getId());
        if (before == null) {
            operationLogService.record("geo", "shop_geo_update", "shop", request.getId(), String.valueOf(request.getId()), null, request, false, "店铺不存在");
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }

        Shop update = new Shop()
                .setId(request.getId())
                .setX(request.getX())
                .setY(request.getY())
                .setPoiId(request.getPoiId())
                .setAdcode(request.getAdcode())
                .setGeoSource(StrUtil.blankToDefault(request.getGeoSource(), "amap"))
                .setGeoStatus(StrUtil.blankToDefault(request.getGeoStatus(), "verified"))
                .setGeoUpdatedAt(LocalDateTime.now());

        updateById(update);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + request.getId());

        Shop after = getById(request.getId());
        refreshShopGeoIndex(before, after);
        operationLogService.record("geo", "shop_geo_update", "shop", request.getId(), String.valueOf(request.getId()), before, after, true, null);
        return Result.ok(after);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Integer size, Double x, Double y) {
        int pageSize = shopPageSize(size);
        //判断是否需要根据坐标查询
        if (x==null||y==null){
            //不需要，直接查
            Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, pageSize));
            return Result.ok(page);
        }
        return queryShopByGeo(SHOP_GEO_KEY + typeId, current, pageSize, x, y);
    }

    @Override
    public Result queryNearbyShops(Integer current, Integer size, Double x, Double y) {
        return queryShopByGeo(SHOP_GEO_ALL_KEY, current, shopPageSize(size), x, y);
    }

    private Result queryShopByGeo(String key, Integer current, int pageSize, Double x, Double y) {
        if (!isValidCoordinate(x, y)) {
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }
        int page = current == null || current < 1 ? 1 : current;
        int from = (page - 1) * pageSize;
        int end = page * pageSize;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().
                search(
                        key,
                        GeoReference.fromCoordinate(x, y), new Distance(nearbyRadiusMeters()),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results==null){
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(pageSize);
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).limit(pageSize).forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        });
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        shops.forEach(shop -> shop.setDistance(distanceMap.get(shop.getId().toString()).getValue()));
        return Result.ok(shops);
    }

    private void refreshShopGeoIndex(Shop before, Shop after) {
        if (after == null || after.getId() == null) {
            return;
        }
        String shopId = after.getId().toString();
        stringRedisTemplate.opsForGeo().remove(SHOP_GEO_ALL_KEY, shopId);
        if (before != null && before.getTypeId() != null) {
            stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + before.getTypeId(), shopId);
        }
        if (after.getTypeId() != null) {
            stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + after.getTypeId(), shopId);
        }
        if (!isGeoIndexable(after)) {
            return;
        }
        Point point = new Point(after.getX(), after.getY());
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_ALL_KEY, point, shopId);
        stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + after.getTypeId(), point, shopId);
    }

    private boolean isGeoIndexable(Shop shop) {
        return shop != null
                && shop.getTypeId() != null
                && isValidCoordinate(shop.getX(), shop.getY())
                && "verified".equals(shop.getGeoStatus());
    }

    private boolean isValidGeoStatus(String status) {
        return "pending".equals(status) || "verified".equals(status) || "failed".equals(status);
    }

    private boolean isValidCoordinate(Double x, Double y) {
        return x != null && y != null && x >= -180 && x <= 180 && y >= -90 && y <= 90;
    }

    private double nearbyRadiusMeters() {
        Double radius = geoProperties == null ? null : geoProperties.getNearbyRadiusMeters();
        return radius == null || radius <= 0 ? 5000D : radius;
    }

    private int shopPageSize(Integer requestedSize) {
        return geoProperties == null
                ? SystemConstants.DEFAULT_PAGE_SIZE
                : geoProperties.resolveShopPageSize(requestedSize);
    }
}
