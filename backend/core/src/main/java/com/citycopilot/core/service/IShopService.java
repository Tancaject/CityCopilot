package com.citycopilot.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopService extends IService<Shop> {
    void assertWritableByCurrentUser(Long shopId);

    @AgentTool(name = "get_shop_detail", description = "Get shop detail by shop id")
    Result queryShopById(Long id);

    Result createShop(Shop shop);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Integer size);

    Result updateShopGeo(ShopGeoUpdateDTO request);
}
