package com.citycopilot.core.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.core.config.AmapProperties;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.AmapPoiDTO;
import com.citycopilot.core.dto.GeoPointDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.utils.RedisGeoCoordinates;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Array;
import java.net.URI;
import java.time.LocalDate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.citycopilot.core.utils.RedisConstants.AMAP_CIRCUIT_KEY;
import static com.citycopilot.core.utils.RedisConstants.AMAP_FAILURE_STREAK_KEY;
import static com.citycopilot.core.utils.RedisConstants.AMAP_METRIC_KEY;
import static com.citycopilot.core.utils.RedisConstants.AMAP_RATE_LIMIT_KEY;
import static com.citycopilot.core.utils.RedisConstants.CACHE_AMAP_GEOCODE_KEY;
import static com.citycopilot.core.utils.RedisConstants.CACHE_AMAP_POI_KEY;

/**
 * 高德地图 Web Service 适配服务，提供地址解析、POI 文本检索和周边检索。
 *
 * <p>所有远端调用统一经过 Redis 缓存、分钟限流、连续失败熔断和指标记录，外部响应再转换为 Core DTO。</p>
 */
@Slf4j
@Service
public class AmapGeoService {

    /** 高德分钟限流计数器使用的时间桶格式。 */
    private static final DateTimeFormatter RATE_LIMIT_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 配置连接和读取超时的高德 HTTP 客户端。 */
    private final RestTemplate restTemplate;

    /** 高德 URL、Web Service Key、缓存和熔断参数。 */
    @Resource
    private AmapProperties amapProperties;

    /** 默认城市、城市限制、附近半径等业务配置。 */
    @Resource
    private GeoProperties geoProperties;

    /** Redis 客户端，承载响应缓存、限流桶、熔断状态和指标。 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** @param builder Spring HTTP 客户端构建器 */
    public AmapGeoService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 将文本地址解析为经纬度和行政区信息。
     *
     * @param address 待解析详细地址
     * @param city 可选城市范围；空值使用 GEO 默认城市
     * @return 地理点或稳定业务错误
     */
    @AgentTool(
            name = "geocode_address",
            description = "Geocode an address into coordinates",
            requiredPermissions = {PermissionCodes.GEO_READ}
    )
    public Result geocode(String address, String city) {
        String operation = "geocode";
        if (StrUtil.isBlank(address)) {
            return Result.fail("BAD_REQUEST", "地址不能为空");
        }
        if (StrUtil.isBlank(amapProperties.getWebServiceKey())) {
            return Result.fail("AMAP_CONFIG_MISSING", "高德地图 Web 服务 Key 未配置");
        }

        String scopeCity = resolveCity(city);
        String cacheKey = CACHE_AMAP_GEOCODE_KEY + cacheKey(address, scopeCity);
        Map<?, ?> cached = readCachedResponse(cacheKey);
        if (cached != null) {
            recordMetric(operation, "cache_hit");
            return parseGeocodeResponse(cached);
        }
        recordMetric(operation, "cache_miss");

        Result gate = checkRemoteGate(operation);
        if (gate != null) {
            return gate;
        }

        URI uri = UriComponentsBuilder.fromUriString(amapProperties.getGeocodeUrl())
                .queryParam("key", amapProperties.getWebServiceKey())
                .queryParam("address", address)
                .queryParamIfPresent("city", StrUtil.isBlank(scopeCity) ? Optional.empty() : Optional.of(scopeCity))
                .build()
                .encode()
                .toUri();

        try {
            // 只有高德明确返回成功才缓存，避免把临时配额或服务错误固化到缓存。
            Map<?, ?> response = restTemplate.getForObject(uri, Map.class);
            Result result = parseGeocodeResponse(response);
            recordRemoteResult(operation, response);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                cacheResponse(cacheKey, response);
            }
            return result;
        } catch (Exception error) {
            recordRemoteFailure(operation);
            log.warn("Amap geocode failed: {}", error.getMessage());
            return Result.fail("AMAP_UNAVAILABLE", "地址解析服务暂时不可用");
        }
    }

    /**
     * 按关键词与城市查询高德 POI 候选。
     *
     * @param keywords POI 名称或类别关键词
     * @param city 可选城市名称
     * @param adcode 可选行政区编码，优先于城市名称
     * @return 标准化 POI DTO 列表或稳定业务错误
     */
    @AgentTool(
            name = "search_geo_poi",
            description = "Search map POI candidates by keyword and city",
            requiredPermissions = {PermissionCodes.GEO_READ}
    )
    public Result searchPoi(String keywords, String city, String adcode) {
        String operation = "poi";
        if (StrUtil.isBlank(keywords)) {
            return Result.fail("BAD_REQUEST", "POI 搜索关键字不能为空");
        }
        if (StrUtil.isBlank(amapProperties.getWebServiceKey())) {
            return Result.fail("AMAP_CONFIG_MISSING", "高德地图 Web 服务 Key 未配置");
        }

        String searchCity = StrUtil.blankToDefault(adcode, resolveCity(city));
        String cacheKey = CACHE_AMAP_POI_KEY + cacheKey(keywords, searchCity);
        Map<?, ?> cached = readCachedResponse(cacheKey);
        if (cached != null) {
            recordMetric(operation, "cache_hit");
            return parsePoiSearchResponse(cached);
        }
        recordMetric(operation, "cache_miss");

        Result gate = checkRemoteGate(operation);
        if (gate != null) {
            return gate;
        }

        URI uri = UriComponentsBuilder.fromUriString(amapProperties.getPoiTextUrl())
                .queryParam("key", amapProperties.getWebServiceKey())
                .queryParam("keywords", keywords)
                .queryParamIfPresent("city", StrUtil.isBlank(searchCity) ? Optional.empty() : Optional.of(searchCity))
                .queryParam("citylimit", cityLimitEnabled())
                .queryParam("offset", 10)
                .queryParam("page", 1)
                .queryParam("extensions", "all")
                .build()
                .encode()
                .toUri();

        try {
            Map<?, ?> response = restTemplate.getForObject(uri, Map.class);
            Result result = parsePoiSearchResponse(response);
            recordRemoteResult(operation, response);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                cacheResponse(cacheKey, response);
            }
            return result;
        } catch (Exception error) {
            recordRemoteFailure(operation);
            log.warn("Amap POI search failed: {}", error.getMessage());
            return Result.fail("AMAP_UNAVAILABLE", "POI 搜索服务暂时不可用");
        }
    }

    /**
     * 使用默认类型范围执行周边 POI 查询。
     *
     * @param x 中心经度
     * @param y 中心纬度
     * @param keywords 可选关键词
     * @param current 页码
     * @param size 每页条数
     * @return 周边 POI 候选
     */
    public Result searchAroundPoi(Double x, Double y, String keywords, Integer current, Integer size) {
        return searchAroundPoi(x, y, keywords, null, current, size);
    }

    /**
     * 按中心点、关键词和高德类型编码查询周边 POI。
     *
     * @param x 中心经度
     * @param y 中心纬度
     * @param keywords 可选关键词
     * @param types 可选高德 POI 类型编码串
     * @param current 页码
     * @param size 每页条数，高德单页上限在此收紧
     * @return 按距离排序的 POI 候选
     */
    public Result searchAroundPoi(Double x, Double y, String keywords, String types, Integer current, Integer size) {
        String operation = "poi_around";
        if (!RedisGeoCoordinates.isValid(x, y)) {
            return Result.fail("SHOP_GEO_INVALID", "坐标参数不合法");
        }
        if (StrUtil.isBlank(amapProperties.getWebServiceKey())) {
            return Result.fail("AMAP_CONFIG_MISSING", "高德地图 Web 服务 Key 未配置");
        }

        int page = current == null || current < 1 ? 1 : current;
        int offset = size == null || size < 1 ? 10 : Math.min(size, 25);
        String keywordScope = StrUtil.blankToDefault(keywords, "");
        String typeScope = StrUtil.blankToDefault(types, "");
        String cacheKey = CACHE_AMAP_POI_KEY + "around:" + cacheKey(
                x + "," + y + "|" + keywordScope + "|" + typeScope + "|" + page + "|" + offset,
                String.valueOf(nearbyRadiusMeters())
        );
        Map<?, ?> cached = readCachedResponse(cacheKey);
        if (cached != null) {
            recordMetric(operation, "cache_hit");
            return parsePoiSearchResponse(cached);
        }
        recordMetric(operation, "cache_miss");

        Result gate = checkRemoteGate(operation);
        if (gate != null) {
            return gate;
        }

        URI uri = UriComponentsBuilder.fromUriString(amapProperties.getPoiAroundUrl())
                .queryParam("key", amapProperties.getWebServiceKey())
                .queryParam("location", x + "," + y)
                .queryParam("radius", Math.round(nearbyRadiusMeters()))
                .queryParamIfPresent("keywords", StrUtil.isBlank(keywordScope) ? Optional.empty() : Optional.of(keywordScope))
                .queryParamIfPresent("types", StrUtil.isBlank(typeScope) ? Optional.empty() : Optional.of(typeScope))
                .queryParam("offset", offset)
                .queryParam("page", page)
                .queryParam("extensions", "all")
                .queryParam("sortrule", "distance")
                .build()
                .encode()
                .toUri();

        try {
            Map<?, ?> response = restTemplate.getForObject(uri, Map.class);
            Result result = parsePoiSearchResponse(response);
            recordRemoteResult(operation, response);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                cacheResponse(cacheKey, response);
            }
            return result;
        } catch (Exception error) {
            recordRemoteFailure(operation);
            log.warn("Amap POI around search failed: {}", error.getMessage());
            return Result.fail("AMAP_UNAVAILABLE", "周边搜索服务暂时不可用");
        }
    }

    /**
     * 汇总高德适配层的当日指标、限流和熔断状态。
     *
     * @return 运维状态映射
     */
    @AgentTool(
            name = "get_geo_provider_status",
            description = "Get Amap provider health, rate-limit, and circuit status",
            requiredPermissions = {PermissionCodes.GEO_READ}
    )
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("circuitOpen", isAmapCircuitOpen());
        status.put("circuitTtlSeconds", redisTtlSeconds(AMAP_CIRCUIT_KEY));
        status.put("failureStreak", redisLongValue(AMAP_FAILURE_STREAK_KEY));
        status.put("rateLimitPerMinute", defaultInt(amapProperties.getRateLimitPerMinute(), 60));
        status.put("currentMinuteCalls", currentMinuteCalls());
        status.put("cacheTtlMinutes", defaultInt(amapProperties.getCacheTtlMinutes(), 1440));
        status.put("failureThreshold", circuitFailureThreshold());
        status.put("circuitOpenSeconds", circuitOpenSeconds());
        status.put("todayMetrics", readTodayMetrics());
        return status;
    }

    /**
     * 校验并解析高德地理编码响应。
     *
     * @param response 高德原始 JSON 映射
     * @return 首个匹配地址的地理点
     */
    private Result parseGeocodeResponse(Map<?, ?> response) {
        if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
            return amapFail("geocode", response, "AMAP_GEOCODE_FAILED", "地址解析失败");
        }
        Object geocodesValue = response.get("geocodes");
        if (!(geocodesValue instanceof List<?> geocodes) || geocodes.isEmpty()) {
            return Result.fail("AMAP_NO_MATCH", "没有找到匹配地址");
        }
        if (!(geocodes.get(0) instanceof Map<?, ?> geocode)) {
            return Result.fail("AMAP_RESPONSE_INVALID", "地址解析结果异常");
        }

        String location = String.valueOf(geocode.get("location"));
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return Result.fail("AMAP_RESPONSE_INVALID", "地址坐标结果异常");
        }
        Double x = parseCoordinate(parts[0]);
        Double y = parseCoordinate(parts[1]);
        if (x == null || y == null) {
            return Result.fail("AMAP_RESPONSE_INVALID", "地址坐标结果异常");
        }

        GeoPointDTO point = new GeoPointDTO()
                .setX(x)
                .setY(y)
                .setFormattedAddress(valueOf(geocode.get("formatted_address")))
                .setProvince(valueOf(geocode.get("province")))
                .setCity(valueOf(geocode.get("city")))
                .setDistrict(valueOf(geocode.get("district")))
                .setAdcode(valueOf(geocode.get("adcode")));
        return Result.ok(point);
    }

    /**
     * 解析高德 POI 列表，跳过结构或坐标损坏的单项。
     *
     * @param response 高德原始 JSON 映射
     * @return 标准化 POI 列表
     */
    private Result parsePoiSearchResponse(Map<?, ?> response) {
        if (response == null || !"1".equals(String.valueOf(response.get("status")))) {
            return amapFail("poi", response, "AMAP_POI_FAILED", "POI 搜索失败");
        }
        Object poisValue = response.get("pois");
        if (!(poisValue instanceof List<?> pois)) {
            return Result.ok(new ArrayList<>());
        }

        List<AmapPoiDTO> candidates = new ArrayList<>();
        for (Object item : pois) {
            if (!(item instanceof Map<?, ?> poi)) {
                continue;
            }
            AmapPoiDTO candidate = toPoiDTO(poi);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return Result.ok(candidates);
    }

    /**
     * 将一条高德 POI 映射为 Core DTO，并兼容不同接口的扩展字段结构。
     *
     * @param poi 高德 POI 字段映射
     * @return DTO；位置无效时返回 {@code null}
     */
    private AmapPoiDTO toPoiDTO(Map<?, ?> poi) {
        String location = valueOf(poi.get("location"));
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        Double x = parseCoordinate(parts[0]);
        Double y = parseCoordinate(parts[1]);
        if (x == null || y == null) {
            return null;
        }
        return new AmapPoiDTO()
                .setId(valueOf(poi.get("id")))
                .setName(valueOf(poi.get("name")))
                .setType(valueOf(poi.get("type")))
                .setTypecode(valueOf(poi.get("typecode")))
                .setAddress(valueOf(poi.get("address")))
                .setTel(valueOf(poi.get("tel")))
                .setBusinessArea(valueOf(poi.get("business_area")))
                .setTag(valueOf(poi.get("tag")))
                .setProvinceName(valueOf(poi.get("pname")))
                .setCityName(valueOf(poi.get("cityname")))
                .setAdName(valueOf(poi.get("adname")))
                .setAdcode(valueOf(poi.get("adcode")))
                .setX(x)
                .setY(y)
                .setDistance(parseCoordinate(valueOf(poi.get("distance"))))
                .setImages(firstPhotoUrl(poi.get("photos")))
                .setAvgPrice(parseAveragePrice(firstNonBlank(
                        nestedValue(poi, "biz_ext", "cost"),
                        nestedValue(poi, "business", "cost")
                )))
                .setScore(parsePoiScore(firstNonBlank(
                        nestedValue(poi, "biz_ext", "rating"),
                        nestedValue(poi, "business", "rating")
                )));
    }

    /**
     * 从嵌套高德对象中安全读取文本字段。
     *
     * @param source 外层 POI
     * @param objectKey 嵌套对象字段名
     * @param valueKey 目标字段名
     * @return 标准化文本或 {@code null}
     */
    private String nestedValue(Map<?, ?> source, String objectKey, String valueKey) {
        Object nested = source.get(objectKey);
        if (!(nested instanceof Map<?, ?> nestedMap)) {
            return null;
        }
        return valueOf(nestedMap.get(valueKey));
    }

    /**
     * @param values 候选文本
     * @return 第一个非空文本
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 从高德 photos 字段的列表或单对象形态中取得第一张图片。
     *
     * @param photosValue 原始 photos 字段
     * @return 首个有效 URL
     */
    private String firstPhotoUrl(Object photosValue) {
        if (photosValue instanceof List<?> photos) {
            for (Object photo : photos) {
                String url = photoUrl(photo);
                if (StrUtil.isNotBlank(url)) {
                    return url;
                }
            }
            return null;
        }
        return photoUrl(photosValue);
    }

    private String photoUrl(Object photo) {
        if (!(photo instanceof Map<?, ?> photoMap)) {
            return null;
        }
        return valueOf(photoMap.get("url"));
    }

    /**
     * 把高德人均消费金额转为项目使用的整数值。
     *
     * @param value 原始金额文本
     * @return 四舍五入后的正数金额
     */
    private Long parseAveragePrice(String value) {
        Double amount = parseCoordinate(value);
        if (amount == null || amount <= 0D) {
            return null;
        }
        return Math.round(amount);
    }

    /**
     * 将高德五分制评分转换为项目的十分倍整数表示。
     *
     * @param value 原始评分文本
     * @return 评分乘十后的整数
     */
    private Integer parsePoiScore(String value) {
        Double rating = parseCoordinate(value);
        if (rating == null || rating <= 0D) {
            return null;
        }
        return (int) Math.round(rating * 10D);
    }

    /**
     * 将高德可能返回的标量、数组或列表统一为第一个有效字符串。
     *
     * @param value 原始字段值
     * @return 有效文本或 {@code null}
     */
    private String valueOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> values) {
            for (Object item : values) {
                String text = valueOf(item);
                if (StrUtil.isNotBlank(text)) {
                    return text;
                }
            }
            return null;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                String text = valueOf(Array.get(value, index));
                if (StrUtil.isNotBlank(text)) {
                    return text;
                }
            }
            return null;
        }
        if (value instanceof Map<?, ?> values && values.isEmpty()) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StrUtil.isBlank(text) || "[]".equals(text) || "{}".equals(text) ? null : text;
    }

    private Double parseCoordinate(String value) {
        try {
            return Double.valueOf(value);
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * 生成不暴露原始地址或关键词的稳定缓存键摘要。
     *
     * @param value 主查询值
     * @param scope 城市、半径等作用域
     * @return MD5 缓存键片段
     */
    private String cacheKey(String value, String scope) {
        return DigestUtil.md5Hex(normalize(scope) + "|" + normalize(value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String resolveCity(String city) {
        return geoProperties == null ? city : geoProperties.resolveCity(city);
    }

    private boolean cityLimitEnabled() {
        return geoProperties == null || geoProperties.isCityLimitEnabled();
    }

    private double nearbyRadiusMeters() {
        Double radius = geoProperties == null ? null : geoProperties.getNearbyRadiusMeters();
        return radius == null || radius <= 0D ? 5000D : radius;
    }

    /**
     * 尽力读取并反序列化高德原始响应缓存；缓存故障不阻断远端主流程。
     *
     * @param key Redis 缓存键
     * @return 缓存响应，未命中或损坏时为空
     */
    private Map<?, ?> readCachedResponse(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return JSONUtil.toBean(json, Map.class);
        } catch (Exception error) {
            log.debug("Read Amap cache failed: {}", error.getMessage());
            return null;
        }
    }

    /**
     * 按配置 TTL 尽力缓存成功的高德原始响应。
     *
     * @param key Redis 缓存键
     * @param response 高德成功响应
     */
    private void cacheResponse(String key, Map<?, ?> response) {
        if (response == null) {
            return;
        }
        Integer ttl = amapProperties.getCacheTtlMinutes();
        int ttlMinutes = ttl == null ? 1440 : ttl;
        if (ttlMinutes <= 0) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(response), ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception error) {
            log.debug("Write Amap cache failed: {}", error.getMessage());
        }
    }

    /**
     * 使用 Redis 按分钟计数器判断整个服务集群是否超过高德调用上限。
     *
     * <p>Redis 故障时放行，由高德自身配额继续兜底。</p>
     *
     * @return 是否应在本地拒绝本次远端调用
     */
    private boolean isAmapRateLimited() {
        Integer limit = amapProperties.getRateLimitPerMinute();
        if (limit == null || limit <= 0) {
            return false;
        }
        String key = AMAP_RATE_LIMIT_KEY + LocalDateTime.now().format(RATE_LIMIT_BUCKET);
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (Long.valueOf(1L).equals(count)) {
                stringRedisTemplate.expire(key, 70, TimeUnit.SECONDS);
            }
            return count != null && count > limit;
        } catch (Exception error) {
            log.debug("Amap rate limit check skipped: {}", error.getMessage());
            return false;
        }
    }

    /**
     * 将高德 status=0 响应转成稳定 Core 错误，并保留 info 便于诊断。
     *
     * @param operation 操作名
     * @param response 高德原始响应
     * @param code 稳定错误码
     * @param fallback 默认错误信息
     * @return 失败结果
     */
    private Result amapFail(String operation, Map<?, ?> response, String code, String fallback) {
        if (response == null) {
            return Result.fail(code, fallback);
        }
        String info = valueOf(response.get("info"));
        String infocode = valueOf(response.get("infocode"));
        log.warn("Amap {} failed, infocode={}, info={}", operation, infocode, info);
        if (StrUtil.isBlank(info)) {
            return Result.fail(code, fallback);
        }
        return Result.fail(code, fallback + "：" + info);
    }

    /**
     * 在发出远端请求前依次检查熔断和分钟限流，并记录调用指标。
     *
     * @param operation 操作名
     * @return 被门禁拒绝时的失败结果；允许调用时为空
     */
    private Result checkRemoteGate(String operation) {
        if (isAmapCircuitOpen()) {
            recordMetric(operation, "circuit_open");
            return Result.fail("AMAP_CIRCUIT_OPEN", "高德地图服务暂时熔断，请稍后再试");
        }
        if (isAmapRateLimited()) {
            recordMetric(operation, "rate_limited");
            return Result.fail("AMAP_RATE_LIMITED", "高德地图服务调用过于频繁，请稍后再试");
        }
        recordMetric(operation, "remote_call");
        return null;
    }

    /**
     * 根据高德业务 status 更新成功或失败连续计数。
     *
     * @param operation 操作名
     * @param response 高德响应
     */
    private void recordRemoteResult(String operation, Map<?, ?> response) {
        if (response != null && "1".equals(String.valueOf(response.get("status")))) {
            recordRemoteSuccess(operation);
            return;
        }
        recordRemoteFailure(operation);
    }

    /**
     * 记录远端成功并清除连续失败计数。
     *
     * @param operation 操作名
     */
    private void recordRemoteSuccess(String operation) {
        recordMetric(operation, "success");
        try {
            stringRedisTemplate.delete(AMAP_FAILURE_STREAK_KEY);
        } catch (Exception error) {
            log.debug("Clear Amap failure streak skipped: {}", error.getMessage());
        }
    }

    /**
     * 递增连续失败计数，达到阈值后创建带 TTL 的共享熔断键。
     *
     * @param operation 操作名
     */
    private void recordRemoteFailure(String operation) {
        recordMetric(operation, "failure");
        try {
            Long count = stringRedisTemplate.opsForValue().increment(AMAP_FAILURE_STREAK_KEY);
            if (Long.valueOf(1L).equals(count)) {
                stringRedisTemplate.expire(AMAP_FAILURE_STREAK_KEY, metricsTtlDays(), TimeUnit.DAYS);
            }
            if (count != null && count >= circuitFailureThreshold()) {
                stringRedisTemplate.opsForValue().set(AMAP_CIRCUIT_KEY, "open", circuitOpenSeconds(), TimeUnit.SECONDS);
            }
        } catch (Exception error) {
            log.debug("Record Amap failure skipped: {}", error.getMessage());
        }
    }

    /** @return 共享高德熔断键当前是否存在 */
    private boolean isAmapCircuitOpen() {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AMAP_CIRCUIT_KEY));
        } catch (Exception error) {
            log.debug("Amap circuit check skipped: {}", error.getMessage());
            return false;
        }
    }

    /**
     * 按天在 Redis Hash 中累加操作和全局指标。
     *
     * @param operation 操作名
     * @param event 指标事件名
     */
    private void recordMetric(String operation, String event) {
        try {
            String key = AMAP_METRIC_KEY + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            stringRedisTemplate.opsForHash().increment(key, operation + "." + event, 1L);
            stringRedisTemplate.opsForHash().increment(key, "total." + event, 1L);
            stringRedisTemplate.expire(key, metricsTtlDays(), TimeUnit.DAYS);
        } catch (Exception error) {
            log.debug("Record Amap metric skipped: {}", error.getMessage());
        }
    }

    /** @return 今天的高德操作指标；Redis 不可用时返回空映射 */
    private Map<String, Long> readTodayMetrics() {
        Map<String, Long> metrics = new LinkedHashMap<>();
        try {
            String key = AMAP_METRIC_KEY + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
            entries.forEach((field, value) -> metrics.put(String.valueOf(field), parseLong(String.valueOf(value))));
        } catch (Exception error) {
            log.debug("Read Amap metrics skipped: {}", error.getMessage());
        }
        return metrics;
    }

    private long currentMinuteCalls() {
        String key = AMAP_RATE_LIMIT_KEY + LocalDateTime.now().format(RATE_LIMIT_BUCKET);
        return redisLongValue(key);
    }

    private long redisLongValue(String key) {
        try {
            return parseLong(stringRedisTemplate.opsForValue().get(key));
        } catch (Exception error) {
            log.debug("Read Redis long value skipped: {}", error.getMessage());
            return 0L;
        }
    }

    private long redisTtlSeconds(String key) {
        try {
            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            return ttl == null || ttl < 0 ? 0L : ttl;
        } catch (Exception error) {
            log.debug("Read Redis ttl skipped: {}", error.getMessage());
            return 0L;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception error) {
            return 0L;
        }
    }

    private int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private int circuitFailureThreshold() {
        Integer threshold = amapProperties.getCircuitFailureThreshold();
        return threshold == null || threshold <= 0 ? 5 : threshold;
    }

    private int circuitOpenSeconds() {
        Integer seconds = amapProperties.getCircuitOpenSeconds();
        return seconds == null || seconds <= 0 ? 120 : seconds;
    }

    private int metricsTtlDays() {
        Integer days = amapProperties.getMetricsTtlDays();
        return days == null || days <= 0 ? 7 : days;
    }
}
