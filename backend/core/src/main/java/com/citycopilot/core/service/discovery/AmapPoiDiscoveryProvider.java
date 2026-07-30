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

    private static final String CONSUMER_POI_TYPES = "050000|060000|070000|080000|090000|100000";
    private static final List<String> STANDARD_CONSUMER_TYPECODE_PREFIXES = List.of("05", "06", "07", "09", "10");
    private static final List<String> CONSUMER_TYPE_KEYWORDS = List.of(
            "餐饮服务", "购物服务", "生活服务", "医疗保健服务", "住宿服务",
            "KTV", "歌厅", "酒吧", "健身", "瑜伽", "游泳馆", "台球", "棋牌",
            "密室", "轰趴", "游乐", "电玩城", "舞蹈", "拳击", "攀岩", "保龄球",
            "羽毛球馆", "网球馆", "篮球馆", "球馆"
    );
    private static final List<String> NON_CONSUMER_TYPE_KEYWORDS = List.of(
            "商务住宅", "住宅区", "住宅小区", "宿舍", "科教文化", "学校", "交通设施", "道路附属",
            "地名地址", "政府机构", "公司企业", "产业园", "停车场", "公共设施",
            "运动场所", "田径场", "足球场", "篮球场", "操场", "体育场", "体育馆"
    );
    private static final List<String> NON_CONSUMER_NAME_KEYWORDS = List.of(
            "学生公寓", "宿舍", "公寓", "住宅", "小区", "教学楼", "图书馆", "办公楼",
            "写字楼", "行政楼", "停车场", "公共厕所", "卫生间", "地铁站", "公交站", "出入口",
            "运动场", "田径场", "足球场", "篮球场", "操场", "体育场"
    );

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
        Result result = amapGeoService.searchAroundPoi(
                query.getX(),
                query.getY(),
                keywords,
                CONSUMER_POI_TYPES,
                1,
                query.resolveCandidateLimit(geoProperties)
        );
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
                .filter(this::isConsumerPoi)
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

    private boolean isConsumerPoi(AmapPoiDTO poi) {
        if (poi == null || StrUtil.isBlank(poi.getName())) {
            return false;
        }
        if (containsAny(poi.getType(), NON_CONSUMER_TYPE_KEYWORDS) || containsAny(poi.getName(), NON_CONSUMER_NAME_KEYWORDS)) {
            return false;
        }
        return hasConsumerTypeCode(poi) || containsAny(poi.getType(), CONSUMER_TYPE_KEYWORDS);
    }

    private boolean hasConsumerTypeCode(AmapPoiDTO poi) {
        String typecode = poi.getTypecode();
        if (StrUtil.isBlank(typecode)) {
            return false;
        }
        if (typecode.startsWith("08")) {
            return containsAny(poi.getType(), CONSUMER_TYPE_KEYWORDS) || containsAny(poi.getName(), CONSUMER_TYPE_KEYWORDS);
        }
        return STANDARD_CONSUMER_TYPECODE_PREFIXES.stream().anyMatch(typecode::startsWith);
    }

    private boolean containsAny(String value, List<String> keywords) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        return keywords.stream().anyMatch(value::contains);
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
                .setImages(poi.getImages())
                .setAddress(poi.getAddress())
                .setTel(poi.getTel())
                .setBusinessArea(poi.getBusinessArea())
                .setTag(poi.getTag())
                .setArea(StrUtil.blankToDefault(poi.getAdName(), poi.getCityName()))
                .setX(poi.getX())
                .setY(poi.getY())
                .setPoiId(poi.getId())
                .setAdcode(poi.getAdcode())
                .setGeoSource("amap")
                .setGeoStatus("external")
                .setAvgPrice(poi.getAvgPrice())
                .setScore(poi.getScore())
                .setDistance(distance);
    }
}
