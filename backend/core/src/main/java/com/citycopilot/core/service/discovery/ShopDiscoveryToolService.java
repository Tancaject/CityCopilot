package com.citycopilot.core.service.discovery;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 面向 Agent 的本地店铺发现工具。
 *
 * <p>与消费者接口复用同一个本地 Provider，避免工具调用和页面查询产生不同排序或可下单边界。</p>
 */
@Service
public class ShopDiscoveryToolService {

    /** 本地事实源发现实现。 */
    private final LocalShopDiscoveryProvider localProvider;

    /** 分页大小和候选上限配置。 */
    private final GeoProperties geoProperties;

    /**
     * @param localProvider 本地店铺发现实现
     * @param geoProperties GEO 分页配置
     */
    public ShopDiscoveryToolService(
            LocalShopDiscoveryProvider localProvider,
            GeoProperties geoProperties
    ) {
        this.localProvider = localProvider;
        this.geoProperties = geoProperties;
    }

    /**
     * 按分类搜索可下单的本地店铺。
     *
     * @param typeId 必填店铺分类主键
     * @param current 可选页码
     * @param size 可选每页条数
     * @param x 可选中心经度；与 {@code y} 同时提供
     * @param y 可选中心纬度；与 {@code x} 同时提供
     * @return 分页候选及总数
     */
    @AgentTool(
            name = "search_shops_by_type",
            description = "Search orderable local shops by category, page, and optional location",
            optionalParameters = {"current", "size", "x", "y"}
    )
    public Result searchByType(
            Integer typeId,
            Integer current,
            Integer size,
            Double x,
            Double y
    ) {
        return discoverLocal(new ShopDiscoveryQuery()
                .setTypeId(typeId)
                .setCurrent(current)
                .setSize(size)
                .setX(x)
                .setY(y));
    }

    /**
     * 搜索当前位置附近的可下单店铺，并可使用偏好分类调整本地排序。
     *
     * @param current 可选页码
     * @param size 可选每页条数
     * @param x 中心经度
     * @param y 中心纬度
     * @param preferredCategory 可选用户偏好提示，不改变权限或硬过滤条件
     * @return 分页候选及总数
     */
    @AgentTool(
            name = "search_nearby_shops",
            description = "Search orderable local shops around the user's current location, optionally prioritizing shop names that match a preferred category hint",
            optionalParameters = {"current", "size", "preferredCategory"}
    )
    public Result searchNearby(
            Integer current,
            Integer size,
            Double x,
            Double y,
            String preferredCategory
    ) {
        if (x == null || y == null) {
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }
        return discoverLocal(new ShopDiscoveryQuery()
                .setCurrent(current)
                .setSize(size)
                .setX(x)
                .setY(y)
                .setPreferredCategory(preferredCategory));
    }

    /**
     * 不带偏好提示的兼容重载。
     *
     * @param current 页码
     * @param size 每页条数
     * @param x 中心经度
     * @param y 中心纬度
     * @return 分页候选及总数
     */
    public Result searchNearby(
            Integer current,
            Integer size,
            Double x,
            Double y
    ) {
        return searchNearby(current, size, x, y, null);
    }

    /**
     * 执行本地发现并应用统一分页。
     *
     * @param query 已组装的查询条件
     * @return 当前页及完整候选总数
     */
    private Result discoverLocal(ShopDiscoveryQuery query) {
        if (query.hasPoint() && !query.hasValidPoint()) {
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }
        int pageSize = query.resolvePageSize(geoProperties);
        long from = query.resolveOffset(geoProperties);
        List<ShopDiscoveryItemDTO> candidates = localProvider.discover(query);
        if (from >= candidates.size()) {
            return Result.ok(List.of(), (long) candidates.size());
        }
        int fromIndex = (int) from;
        int to = (int) Math.min(from + pageSize, candidates.size());
        return Result.ok(candidates.subList(fromIndex, to), (long) candidates.size());
    }
}
