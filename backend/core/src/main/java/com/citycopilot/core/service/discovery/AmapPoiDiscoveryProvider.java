package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.AmapPoiDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.dto.ShopTypeDTO;
import com.citycopilot.core.service.AmapGeoService;
import com.citycopilot.core.service.IShopTypeService;
import com.citycopilot.core.utils.RedisGeoCoordinates;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 高德地图 POI 店铺发现数据源，为本地库之外的消费场所提供补充候选。
 *
 * <p>外部 POI 只用于发现，统一标记为不可下单；消费类型过滤用于排除住宅、学校和公共设施。</p>
 */
@Slf4j
@Component
public class AmapPoiDiscoveryProvider implements ShopDiscoveryProvider {

    /** 传给高德周边检索的消费类大类编码。 */
    private static final String CONSUMER_POI_TYPES = "050000|060000|070000|080000|090000|100000";

    /** 可直接判定为消费场所的标准高德 typecode 前缀。 */
    private static final List<String> STANDARD_CONSUMER_TYPECODE_PREFIXES = List.of("05", "06", "07", "09", "10");

    /** 对娱乐等混合分类进行正向识别的类型关键词。 */
    private static final List<String> CONSUMER_TYPE_KEYWORDS = List.of(
            "餐饮服务", "购物服务", "生活服务", "医疗保健服务", "住宿服务",
            "KTV", "歌厅", "酒吧", "健身", "瑜伽", "游泳馆", "台球", "棋牌",
            "密室", "轰趴", "游乐", "电玩城", "舞蹈", "拳击", "攀岩", "保龄球",
            "羽毛球馆", "网球馆", "篮球馆", "球馆"
    );
    /** 明确排除的非消费 POI 类型关键词。 */
    private static final List<String> NON_CONSUMER_TYPE_KEYWORDS = List.of(
            "商务住宅", "住宅区", "住宅小区", "宿舍", "科教文化", "学校", "交通设施", "道路附属",
            "地名地址", "政府机构", "公司企业", "产业园", "停车场", "公共设施",
            "运动场所", "田径场", "足球场", "篮球场", "操场", "体育场", "体育馆"
    );
    /** 类型信息模糊时仍可按名称排除的非消费设施。 */
    private static final List<String> NON_CONSUMER_NAME_KEYWORDS = List.of(
            "学生公寓", "宿舍", "公寓", "住宅", "小区", "教学楼", "图书馆", "办公楼",
            "写字楼", "行政楼", "停车场", "公共厕所", "卫生间", "地铁站", "公交站", "出入口",
            "运动场", "田径场", "足球场", "篮球场", "操场", "体育场"
    );

    /** 高德 Web Service 封装，负责周边 POI HTTP 调用和审计。 */
    @Resource
    private AmapGeoService amapGeoService;

    /** 店铺分类字典，用于把本地分类主键转换为高德关键词。 */
    @Resource
    private IShopTypeService shopTypeService;

    /** 外部候选读取上限配置。 */
    @Resource
    private GeoProperties geoProperties;

    @Override
    public List<ShopDiscoveryItemDTO> discover(ShopDiscoveryQuery query) {
        if (query == null || !query.hasValidPoint()) {
            return List.of();
        }
        String keywords = resolveKeywords(query);
        // 复用 AmapGeoService 的鉴权、限流和响应解析，Provider 只处理发现语义。
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

    /**
     * 优先使用显式关键词，否则将本地店铺分类名称作为高德搜索词。
     *
     * @param query 发现查询
     * @return 高德关键词；无过滤时返回空串
     */
    private String resolveKeywords(ShopDiscoveryQuery query) {
        if (StrUtil.isNotBlank(query.getKeyword())) {
            return query.normalizedKeyword();
        }
        if (query.getTypeId() == null) {
            return "";
        }
        ShopTypeDTO shopType = shopTypeService.findShopType(query.getTypeId().longValue());
        return shopType == null ? "" : StrUtil.blankToDefault(shopType.name(), "");
    }

    /**
     * 结合类型编码和关键词判断 POI 是否属于可展示的消费场所。
     *
     * @param poi 高德 POI
     * @return 是否保留为外部发现候选
     */
    private boolean isConsumerPoi(AmapPoiDTO poi) {
        if (poi == null || StrUtil.isBlank(poi.getName())) {
            return false;
        }
        if (containsAny(poi.getType(), NON_CONSUMER_TYPE_KEYWORDS) || containsAny(poi.getName(), NON_CONSUMER_NAME_KEYWORDS)) {
            return false;
        }
        return hasConsumerTypeCode(poi) || containsAny(poi.getType(), CONSUMER_TYPE_KEYWORDS);
    }

    /**
     * 识别消费类高德 typecode；08 娱乐体育大类还需关键词二次确认。
     *
     * @param poi 高德 POI
     * @return 类型编码是否符合消费场所边界
     */
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

    /**
     * 将高德 POI 转换成统一发现 DTO，并明确标记为不可下单。
     *
     * @param poi 高德 POI
     * @param query 用于补算距离的查询中心
     * @return 外部发现候选
     */
    private ShopDiscoveryItemDTO toItem(AmapPoiDTO poi, ShopDiscoveryQuery query) {
        Double distance = poi.getDistance();
        // 部分高德响应不带距离，坐标合法时在本地补算以维持统一排序。
        if (distance == null && RedisGeoCoordinates.isValid(poi.getX(), poi.getY())) {
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
