package com.citycopilot.core.service.discovery;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent-facing compatibility tools backed by the same local discovery provider
 * used by the consumer discovery chain.
 */
@Service
public class ShopDiscoveryToolService {

    private final LocalShopDiscoveryProvider localProvider;
    private final GeoProperties geoProperties;

    public ShopDiscoveryToolService(
            LocalShopDiscoveryProvider localProvider,
            GeoProperties geoProperties
    ) {
        this.localProvider = localProvider;
        this.geoProperties = geoProperties;
    }

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

    @AgentTool(
            name = "search_nearby_shops",
            description = "Search orderable local shops around the user's current location",
            optionalParameters = {"current", "size"}
    )
    public Result searchNearby(
            Integer current,
            Integer size,
            Double x,
            Double y
    ) {
        if (x == null || y == null) {
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }
        return discoverLocal(new ShopDiscoveryQuery()
                .setCurrent(current)
                .setSize(size)
                .setX(x)
                .setY(y));
    }

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
