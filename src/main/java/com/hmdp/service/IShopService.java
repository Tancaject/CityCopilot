package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
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

    Result queryShopById(Long id);

    Result createShop(Shop shop);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Integer size, Double x, Double y);

    Result queryNearbyShops(Integer current, Integer size, Double x, Double y);

    Result updateShopGeo(ShopGeoUpdateDTO request);
}
