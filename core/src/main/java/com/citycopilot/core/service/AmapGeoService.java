package com.citycopilot.core.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.config.AmapProperties;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.AmapPoiDTO;
import com.citycopilot.core.dto.GeoPointDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

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

@Slf4j
@Service
public class AmapGeoService {

    private static final DateTimeFormatter RATE_LIMIT_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final RestTemplate restTemplate;

    @Resource
    private AmapProperties amapProperties;

    @Resource
    private GeoProperties geoProperties;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public AmapGeoService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

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

        URI uri = UriComponentsBuilder.fromHttpUrl(amapProperties.getGeocodeUrl())
                .queryParam("key", amapProperties.getWebServiceKey())
                .queryParam("address", address)
                .queryParamIfPresent("city", StrUtil.isBlank(scopeCity) ? Optional.empty() : Optional.of(scopeCity))
                .build()
                .encode()
                .toUri();

        try {
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

        URI uri = UriComponentsBuilder.fromHttpUrl(amapProperties.getPoiTextUrl())
                .queryParam("key", amapProperties.getWebServiceKey())
                .queryParam("keywords", keywords)
                .queryParamIfPresent("city", StrUtil.isBlank(searchCity) ? Optional.empty() : Optional.of(searchCity))
                .queryParam("citylimit", cityLimitEnabled())
                .queryParam("offset", 10)
                .queryParam("page", 1)
                .queryParam("extensions", "base")
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
                .setProvinceName(valueOf(poi.get("pname")))
                .setCityName(valueOf(poi.get("cityname")))
                .setAdName(valueOf(poi.get("adname")))
                .setAdcode(valueOf(poi.get("adcode")))
                .setX(x)
                .setY(y);
    }

    private String valueOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double parseCoordinate(String value) {
        try {
            return Double.valueOf(value);
        } catch (Exception error) {
            return null;
        }
    }

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

    private void recordRemoteResult(String operation, Map<?, ?> response) {
        if (response != null && "1".equals(String.valueOf(response.get("status")))) {
            recordRemoteSuccess(operation);
            return;
        }
        recordRemoteFailure(operation);
    }

    private void recordRemoteSuccess(String operation) {
        recordMetric(operation, "success");
        try {
            stringRedisTemplate.delete(AMAP_FAILURE_STREAK_KEY);
        } catch (Exception error) {
            log.debug("Clear Amap failure streak skipped: {}", error.getMessage());
        }
    }

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

    private boolean isAmapCircuitOpen() {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AMAP_CIRCUIT_KEY));
        } catch (Exception error) {
            log.debug("Amap circuit check skipped: {}", error.getMessage());
            return false;
        }
    }

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
