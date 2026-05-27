package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.AmapPoiDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.entity.ShopType;
import com.citycopilot.core.service.AmapGeoService;
import com.citycopilot.core.service.IShopTypeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class AmapPoiDiscoveryProvider implements ShopDiscoveryProvider {

    @Resource
    private AmapGeoService amapGeoService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private GeoProperties geoProperties;

    @Override
    public List<ShopDiscoveryItemDTO> discover(ShopDiscoveryQuery query) {
        if (query == null || !query.hasValidPoint()) {
            return List.of();
        }
        String keywords = resolveKeywords(query);
        Result result = amapGeoService.searchAroundPoi(query.getX(), query.getY(), keywords, 1, query.resolveFetchLimit(geoProperties));
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            log.debug("Amap discovery skipped: {} {}", result.getCode(), result.getErrorMsg());
            return List.of();
        }
        if (!(result.getData() instanceof List<?> pois)) {
            return List.of();
        }
        return pois.stream()
                .filter(AmapPoiDTO.class::isInstance)
                .map(AmapPoiDTO.class::cast)
                .map(poi -> toItem(poi, query))
                .toList();
    }

    private String resolveKeywords(ShopDiscoveryQuery query) {
        if (StrUtil.isNotBlank(query.getKeyword())) {
            return query.normalizedKeyword();
        }
        if (query.getTypeId() == null) {
            return "";
        }
        ShopType shopType = shopTypeService.getById(query.getTypeId());
        return shopType == null ? "" : StrUtil.blankToDefault(shopType.getName(), "");
    }

    private ShopDiscoveryItemDTO toItem(AmapPoiDTO poi, ShopDiscoveryQuery query) {
        Double distance = poi.getDistance();
        if (distance == null && DiscoveryGeoUtils.isValidCoordinate(poi.getX(), poi.getY())) {
            distance = DiscoveryGeoUtils.distanceMeters(query.getX(), query.getY(), poi.getX(), poi.getY());
        }
        return new ShopDiscoveryItemDTO()
                .setSource("amap")
                .setSourceId(StrUtil.blankToDefault(poi.getId(), poi.getName()))
                .setOrderable(false)
                .setTypeName(poi.getType())
                .setName(poi.getName())
                .setAddress(poi.getAddress())
                .setArea(StrUtil.blankToDefault(poi.getAdName(), poi.getCityName()))
                .setX(poi.getX())
                .setY(poi.getY())
                .setPoiId(poi.getId())
                .setAdcode(poi.getAdcode())
                .setGeoSource("amap")
                .setGeoStatus("external")
                .setDistance(distance);
    }
}
