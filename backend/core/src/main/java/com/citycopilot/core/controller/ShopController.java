package com.citycopilot.core.controller;


import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.discovery.LegacyShopGeoQueryAdapter;
import com.citycopilot.core.service.discovery.ShopDiscoveryQuery;
import com.citycopilot.core.service.discovery.ShopDiscoveryService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import jakarta.annotation.Resource;

/**
 * 店铺资料与发现控制器。
 *
 * <p>基础资料以 MySQL 店铺服务为事实源；附近查询兼容旧接口，本地发现可使用 Redis GEO
 * 等投影；统一发现接口还会编排本地与高德等 Provider。只读投影角色可通过配置关闭本控制器。</p>
 */
@RestController
@RequestMapping("/shop")
@ConditionalOnProperty(
        prefix = "citycopilot.projection.roles",
        name = "api-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ShopController {

    /** 店铺事实源服务，负责详情、资料写入和普通分页查询。 */
    @Resource
    public IShopService shopService;

    /** 多来源店铺发现编排器，负责隔离单源故障、去重、排序和分页。 */
    @Resource
    private ShopDiscoveryService shopDiscoveryService;

    /** 旧版附近接口适配器，保留既有店铺字段与距离排序响应。 */
    @Resource
    private LegacyShopGeoQueryAdapter legacyShopGeoQueryAdapter;

    /**
     * 查询店铺详情。
     *
     * @param id 店铺主键
     * @return 店铺详情或服务层的不存在结果
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryShopById(id);
    }

    /**
     * 创建店铺，需要店铺写权限。
     *
     * @param shop 待创建的店铺资料
     * @return 新店铺主键或字段校验失败结果
     */
    @PostMapping
    @RequirePermission(PermissionCodes.SHOP_WRITE)
    public Result saveShop(@RequestBody Shop shop) {
        return shopService.createShop(shop);
    }

    /**
     * 更新店铺基础资料，需要店铺写权限。
     *
     * @param shop 包含店铺主键及变更字段的数据
     * @return 更新结果；缓存失效和审计由店铺服务处理
     */
    @PutMapping
    @RequirePermission(PermissionCodes.SHOP_WRITE)
    public Result updateShop(@RequestBody Shop shop) {
        return shopService.update(shop);
    }

    /**
     * 更新指定店铺的坐标与 GEO 元数据，需要 GEO 写权限。
     *
     * <p>路径主键覆盖正文中的主键，避免调用方把坐标误写到其他店铺；坐标校验、事实库更新、
     * 投影同步和审计由店铺服务负责。</p>
     *
     * @param id URL 中的店铺主键
     * @param request 坐标、POI、行政区码、来源和校验状态
     * @return GEO 更新结果
     */
    @PutMapping("/{id}/geo")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result updateShopGeo(@PathVariable("id") Long id, @RequestBody ShopGeoUpdateDTO request) {
        request.setId(id);
        return shopService.updateShopGeo(request);
    }

    /**
     * 按分类分页查询店铺；携带坐标时切换为附近查询。
     *
     * @param typeId 店铺分类主键
     * @param current 页码，默认第 1 页
     * @param size 可选页大小，受 GEO 配置上限约束
     * @param x 可选中心经度，必须与纬度同时有效
     * @param y 可选中心纬度，必须与经度同时有效
     * @return 普通分类列表，或按距离排序的旧版 GEO 列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "x",required = false)Double x,
            @RequestParam(value = "y",required = false)Double y
    ) {
        if (x == null && y == null) {
            return shopService.queryShopByType(typeId, current, size);
        }
        // 旧 URL 的坐标语义集中交给适配器校验，避免控制器复制 GEO 查询规则。
        return legacyShopGeoQueryAdapter.queryByType(typeId, current, size, x, y);
    }

    /**
     * 查询中心点附近的本地可下单店铺。
     *
     * @param current 页码，默认第 1 页
     * @param size 可选页大小
     * @param x 中心经度
     * @param y 中心纬度
     * @return 兼容旧协议的距离排序店铺列表
     */
    @GetMapping("/nearby")
    public Result queryNearbyShops(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam("x") Double x,
            @RequestParam("y") Double y
    ) {
        return legacyShopGeoQueryAdapter.queryNearby(current, size, x, y);
    }

    /**
     * 跨数据源发现店铺，并统一去重、排序和分页。
     *
     * <p>{@code keyword} 优先，空白时兼容旧客户端的 {@code name}；坐标不完整或越界由发现服务
     * 返回稳定 GEO 错误。外部高德候选只参与发现，不替代本地可下单店铺事实。</p>
     *
     * @param typeId 可选店铺分类
     * @param keyword 可选统一搜索词
     * @param name 兼容旧客户端的名称搜索词
     * @param current 页码
     * @param size 可选页大小
     * @param x 可选中心经度
     * @param y 可选中心纬度
     * @return 合并后的发现结果及总数
     */
    @GetMapping("/discovery")
    public Result discoverShops(
            @RequestParam(value = "typeId", required = false) Integer typeId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
        String resolvedKeyword = StrUtil.blankToDefault(keyword, name);
        ShopDiscoveryQuery query = new ShopDiscoveryQuery()
                .setTypeId(typeId)
                .setKeyword(resolvedKeyword)
                .setCurrent(current)
                .setSize(size)
                .setX(x)
                .setY(y);
        return shopDiscoveryService.discover(query);
    }

    /**
     * 按名称模糊匹配本地店铺。
     *
     * @param name 可选名称关键词；空白时返回未过滤分页
     * @param current 页码，默认第 1 页
     * @param size 可选页大小，按 GEO 配置正规化
     * @return 当前页店铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        return shopService.queryShopByName(name, current, size);
    }
}
