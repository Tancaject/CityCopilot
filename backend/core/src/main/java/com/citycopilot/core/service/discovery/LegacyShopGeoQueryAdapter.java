package com.citycopilot.core.service.discovery;

import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.entity.Shop;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Preserves the legacy HTTP response shape while delegating all coordinate
 * discovery to the current local provider.
 */
@Service
public class LegacyShopGeoQueryAdapter {

    private final LocalShopDiscoveryProvider localProvider;
    private final GeoProperties geoProperties;

    public LegacyShopGeoQueryAdapter(
            LocalShopDiscoveryProvider localProvider,
            GeoProperties geoProperties
    ) {
        this.localProvider = localProvider;
        this.geoProperties = geoProperties;
    }

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
        return Result.ok(candidates.subList(fromIndex, to).stream().map(this::toShop).toList());
    }

    private Shop toShop(ShopDiscoveryItemDTO item) {
        return new Shop()
                .setId(item.getId())
                .setTypeId(item.getTypeId())
                .setName(item.getName())
                .setImages(item.getImages())
                .setArea(item.getArea())
                .setAddress(item.getAddress())
                .setX(item.getX())
                .setY(item.getY())
                .setPoiId(item.getPoiId())
                .setAdcode(item.getAdcode())
                .setGeoSource(item.getGeoSource())
                .setGeoStatus(item.getGeoStatus())
                .setAvgPrice(item.getAvgPrice())
                .setSold(item.getSold())
                .setComments(item.getComments())
                .setScore(item.getScore())
                .setOpenHours(item.getOpenHours())
                .setDistance(item.getDistance());
    }
}
