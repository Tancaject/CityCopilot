package com.citycopilot.core.service;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.dto.ShopViewDTO;
import com.citycopilot.contract.booking.BookingContracts.ShopView;
import java.util.List;
import com.citycopilot.core.entity.Shop;

/**
 * 店铺主数据服务，统一处理店铺查询、写入、坐标维护及写权限校验。
 *
 * <p>店铺变更会进一步驱动缓存失效、Redis GEO 索引和搜索投影更新。</p>
 */
public interface IShopService {

    /** 读取 MySQL 权威快照，供归属校验和坐标维护；不读取详情缓存。 */
    ShopViewDTO findAuthoritativeShop(Long shopId);

    /** 按当前用户角色及 owner 范围返回预约管理入口可见店铺，按 ID 升序。 */
    List<ShopView> listBookingShopsForCurrentUser();

    long countShops();

    long countShopsMissingGeo();

    long countShopsByGeoStatus(String status);

    /**
     * 校验当前用户是否有权修改指定店铺。
     *
     * @param shopId 店铺主键
     * @throws RuntimeException 店铺不存在或当前用户无写权限时抛出业务异常
     */
    void assertWritableByCurrentUser(Long shopId);

    /**
     * 按主键查询店铺详情，优先使用缓存。
     *
     * @param id 店铺主键
     * @return 店铺详情或失败结果
     */
    Result queryShopById(Long id);

    /**
     * 创建店铺并安排相关索引更新。
     *
     * @param shop 待创建的店铺资料
     * @return 创建结果及店铺主键
     */
    Result createShop(Shop shop);

    /**
     * 更新店铺主数据并使缓存和检索投影最终收敛。
     *
     * @param shop 包含主键和待更新字段的店铺对象
     * @return 更新结果
     */
    Result update(Shop shop);

    /** @return 按名称模糊匹配的当前页店铺视图；空白名称不附加过滤 */
    Result queryShopByName(String name, Integer current, Integer size);

    /**
     * 单独更新店铺坐标及核验信息。
     *
     * @param request 坐标、来源和核验状态
     * @return 更新结果
     */
    Result updateShopGeo(ShopGeoUpdateDTO request);
}
