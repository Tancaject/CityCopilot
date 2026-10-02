package com.citycopilot.core.service.discovery;

import com.citycopilot.core.dto.ShopDiscoveryItemDTO;

import java.util.List;

/**
 * 店铺发现数据源扩展点。
 *
 * <p>本地数据库、Redis GEO/搜索投影和高德 POI 可各自实现该接口，编排服务负责合并结果。</p>
 */
@FunctionalInterface
public interface ShopDiscoveryProvider {

    /**
     * 根据统一查询条件返回候选店铺。
     *
     * @param query 已标准化的发现条件
     * @return 当前数据源可提供的候选列表；无结果时返回空列表
     */
    List<ShopDiscoveryItemDTO> discover(ShopDiscoveryQuery query);
}
