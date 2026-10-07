package com.citycopilot.core.service;

import com.citycopilot.core.dto.ShopTypeDTO;

import java.util.List;

/**
 * 店铺分类服务，为店铺查询和发现流程提供按展示顺序排列的分类字典。
 */
public interface IShopTypeService {

    /** 读取权威分类信息；分类不存在时返回 null。 */
    ShopTypeDTO findShopType(Long typeId);

    /**
     * 查询全部店铺分类；实现层会优先读取 Redis 缓存。
     *
     * @return 按 {@code sort} 升序排列的分类列表
     */
    List<ShopTypeDTO> queryShopType();
}
