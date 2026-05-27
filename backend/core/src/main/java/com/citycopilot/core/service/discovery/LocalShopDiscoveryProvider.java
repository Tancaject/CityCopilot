package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.service.IShopService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class LocalShopDiscoveryProvider implements ShopDiscoveryProvider {

    @Resource
    private IShopService shopService;

    @Resource
    private GeoProperties geoProperties;

    @Override
    public List<ShopDiscoveryItemDTO> discover(ShopDiscoveryQuery query) {
        if (query != null && query.hasValidPoint()) {
            return discoverByPoint(query);
        }
        return discoverByPage(query == null ? new ShopDiscoveryQuery() : query);
    }

    private List<ShopDiscoveryItemDTO> discoverByPoint(ShopDiscoveryQuery query) {
        int fetchLimit = Math.max(query.resolveFetchLimit(geoProperties) * 3, query.resolvePageSize(geoProperties));
        double radius = resolveRadiusMeters();
        return baseQuery(query)
                .eq("geo_status", "verified")
                .isNotNull("x")
                .isNotNull("y")
                .last("LIMIT " + Math.min(fetchLimit, 200))
                .list()
                .stream()
                .filter(shop -> DiscoveryGeoUtils.isValidCoordinate(shop.getX(), shop.getY()))
                .map(shop -> toItem(shop, DiscoveryGeoUtils.distanceMeters(query.getX(), query.getY(), shop.getX(), shop.getY())))
                .filter(item -> item.getDistance() != null && item.getDistance() <= radius)
                .sorted(Comparator.comparing(ShopDiscoveryItemDTO::getDistance))
                .limit(query.resolveFetchLimit(geoProperties))
                .toList();
    }

    private List<ShopDiscoveryItemDTO> discoverByPage(ShopDiscoveryQuery query) {
        Page<Shop> page = baseQuery(query).page(new Page<>(1, query.resolveFetchLimit(geoProperties)));
        return page.getRecords().stream()
                .map(shop -> toItem(shop, null))
                .toList();
    }

    private QueryChainWrapper<Shop> baseQuery(ShopDiscoveryQuery query) {
        QueryChainWrapper<Shop> wrapper = shopService.query();
        if (query.getTypeId() != null) {
            wrapper.eq("type_id", query.getTypeId());
        }
        if (StrUtil.isNotBlank(query.getKeyword())) {
            wrapper.like("name", query.normalizedKeyword());
        }
        return wrapper;
    }

    private ShopDiscoveryItemDTO toItem(Shop shop, Double distance) {
        String sourceId = shop.getId() == null ? null : String.valueOf(shop.getId());
        return new ShopDiscoveryItemDTO()
                .setId(shop.getId())
                .setSource("local")
                .setSourceId(sourceId)
                .setOrderable(true)
                .setTypeId(shop.getTypeId())
                .setName(shop.getName())
                .setImages(shop.getImages())
                .setArea(shop.getArea())
                .setAddress(shop.getAddress())
                .setX(shop.getX())
                .setY(shop.getY())
                .setPoiId(shop.getPoiId())
                .setAdcode(shop.getAdcode())
                .setGeoSource(shop.getGeoSource())
                .setGeoStatus(shop.getGeoStatus())
                .setAvgPrice(shop.getAvgPrice())
                .setSold(shop.getSold())
                .setComments(shop.getComments())
                .setScore(shop.getScore())
                .setOpenHours(shop.getOpenHours())
                .setDistance(distance);
    }

    private double resolveRadiusMeters() {
        Double radius = geoProperties == null ? null : geoProperties.getNearbyRadiusMeters();
        return Objects.requireNonNullElse(radius, 5000D);
    }
}
