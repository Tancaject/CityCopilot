package com.citycopilot.core.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.discovery.ShopDiscoveryQuery;
import com.citycopilot.core.service.discovery.ShopDiscoveryService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    @Resource
    private GeoProperties geoProperties;

    @Resource
    private ShopDiscoveryService shopDiscoveryService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryShopById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    @RequirePermission(PermissionCodes.SHOP_WRITE)
    public Result saveShop(@RequestBody Shop shop) {
        return shopService.createShop(shop);
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    @RequirePermission(PermissionCodes.SHOP_WRITE)
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库
        return shopService.update(shop);
    }

    @PutMapping("/{id}/geo")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result updateShopGeo(@PathVariable("id") Long id, @RequestBody ShopGeoUpdateDTO request) {
        request.setId(id);
        return shopService.updateShopGeo(request);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "x",required = false)Double x,
            @RequestParam(value = "y",required = false)Double y
    ) {
        return shopService.queryShopByType(typeId,current,size,x,y);
    }

    @GetMapping("/nearby")
    public Result queryNearbyShops(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam("x") Double x,
            @RequestParam("y") Double y
    ) {
        return shopService.queryNearbyShops(current, size, x, y);
    }

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
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, geoProperties.resolveShopPageSize(size)));
        return Result.ok(page.getRecords());
    }
}
