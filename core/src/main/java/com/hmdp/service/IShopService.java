package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.core.agent.AgentTool;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopGeoUpdateDTO;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    @AgentTool(name = "get_shop_detail", description = "Get shop detail by shop id")
    Result queryShopById(Long id);

    Result createShop(Shop shop);

    Result update(Shop shop);

    @AgentTool(name = "search_shops_by_type", description = "Search shops by category, page, and optional location")
    Result queryShopByType(Integer typeId, Integer current, Integer size, Double x, Double y);

    @AgentTool(name = "search_nearby_shops", description = "Search nearby shops around the user's current location")
    Result queryNearbyShops(Integer current, Integer size, Double x, Double y);

    Result updateShopGeo(ShopGeoUpdateDTO request);
}
