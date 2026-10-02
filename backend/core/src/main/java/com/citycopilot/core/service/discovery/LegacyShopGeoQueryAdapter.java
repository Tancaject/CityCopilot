package com.citycopilot.core.service.discovery;

import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.dto.ShopViewDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 旧版店铺 GEO HTTP 接口适配器。
 *
 * <p>坐标发现统一委托当前本地 Provider，再映射为旧接口要求的店铺读取字段。</p>
 */
@Service
public class LegacyShopGeoQueryAdapter {

    /** 当前本地店铺发现实现。 */
    private final LocalShopDiscoveryProvider localProvider;

    /** 分页大小和候选上限配置。 */
    private final GeoProperties geoProperties;

    /**
     * @param localProvider 本地店铺发现实现
     * @param geoProperties GEO 分页配置
     */
    public LegacyShopGeoQueryAdapter(
            LocalShopDiscoveryProvider localProvider,
            GeoProperties geoProperties
    ) {
        this.localProvider = localProvider;
        this.geoProperties = geoProperties;
    }

    /**
     * 兼容旧接口的分类附近查询。
     *
     * @param typeId 店铺分类主键
     * @param current 页码
     * @param size 每页条数
     * @param x 中心经度
     * @param y 中心纬度
     * @return 旧版店铺列表响应
     */
    public Result queryByType(
            Integer typeId,
            Integer current,
            Integer size,
            Double x,
            Double y
    ) {
        return discover(new ShopDiscoveryQuery()
                .setTypeId(typeId)
                .setCurrent(current)
                .setSize(size)
                .setX(x)
                .setY(y));
    }

    /**
     * 兼容旧接口的全分类附近查询。
     *
     * @param current 页码
     * @param size 每页条数
     * @param x 中心经度
     * @param y 中心纬度
     * @return 旧版店铺列表响应
     */
    public Result queryNearby(
            Integer current,
            Integer size,
            Double x,
            Double y
    ) {
        return discover(new ShopDiscoveryQuery()
                .setCurrent(current)
                .setSize(size)
                .setX(x)
                .setY(y));
    }

    /**
     * 校验坐标、执行发现并在完整候选集上应用旧接口分页。
     *
     * @param query 统一发现条件
     * @return 分页后的旧版响应
     */
    private Result discover(ShopDiscoveryQuery query) {
        if (!query.hasValidPoint()) {
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }
        int pageSize = query.resolvePageSize(geoProperties);
        long from = query.resolveOffset(geoProperties);
        List<ShopDiscoveryItemDTO> candidates = localProvider.discover(query);
        if (from >= candidates.size()) {
            return Result.ok(List.of());
        }
        int fromIndex = (int) from;
        int to = (int) Math.min(from + pageSize, candidates.size());
        return Result.ok(candidates.subList(fromIndex, to).stream().map(this::toView).toList());
    }

    /**
     * 把统一发现 DTO 映射回旧接口字段，不加入统一发现专用字段。
     *
     * @param item 发现候选
     * @return 兼容响应对象
     */
    private ShopViewDTO toView(ShopDiscoveryItemDTO item) {
        return new ShopViewDTO(item.getId(), item.getName(), item.getTypeId(), null,
                item.getImages(), item.getArea(), item.getAddress(), item.getX(), item.getY(),
                item.getPoiId(), item.getAdcode(), item.getGeoSource(), item.getGeoStatus(), null,
                item.getAvgPrice(), item.getSold(), item.getComments(), item.getScore(), item.getOpenHours(),
                null, null, null, item.getDistance());
    }
}
