package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.config.ShopSearchProperties;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LocalShopDiscoveryProvider implements ShopDiscoveryProvider {

    private static final double EARTH_RADIUS_METERS = 6371008.8D;

    private final IShopService shopService;
    private final GeoProperties geoProperties;
    private final RedisShopGeoSearchService redisGeoSearchService;
    private final ShopSearchProjectionService searchProjectionService;
    private final ShopSearchProperties searchProperties;

    @Autowired
    public LocalShopDiscoveryProvider(
            IShopService shopService,
            GeoProperties geoProperties,
            RedisShopGeoSearchService redisGeoSearchService,
            ShopSearchProjectionService searchProjectionService,
            ShopSearchProperties searchProperties
    ) {
        this.shopService = shopService;
        this.geoProperties = geoProperties;
        this.redisGeoSearchService = redisGeoSearchService;
        this.searchProjectionService = searchProjectionService;
        this.searchProperties = searchProperties;
    }

    public LocalShopDiscoveryProvider(
            IShopService shopService,
            GeoProperties geoProperties,
            RedisShopGeoSearchService redisGeoSearchService
    ) {
        this(shopService, geoProperties, redisGeoSearchService, null, new ShopSearchProperties());
    }

    @Override
    public List<ShopDiscoveryItemDTO> discover(ShopDiscoveryQuery query) {
        ShopDiscoveryQuery actualQuery = query == null ? new ShopDiscoveryQuery() : query;
        if (searchProjectionService == null || !searchProjectionService.shouldSearch(actualQuery)) {
            return discoverLegacy(actualQuery);
        }

        if (searchProperties.resolveMode() == ShopSearchProperties.Mode.ELASTICSEARCH) {
            ShopSearchProjectionService.SearchCandidates searchResult = searchProjectionService.search(actualQuery);
            return searchResult.available() ? toItems(searchResult) : discoverLegacy(actualQuery);
        }

        List<ShopDiscoveryItemDTO> legacyItems = discoverLegacy(actualQuery);
        ShopSearchProjectionService.SearchCandidates searchResult = searchProjectionService.search(actualQuery);
        if (searchResult.available()) {
            List<ShopDiscoveryItemDTO> searchItems = toItems(searchResult);
            List<Long> legacyIds = itemIds(legacyItems);
            List<Long> searchIds = itemIds(searchItems);
            if (!legacyIds.equals(searchIds)) {
                log.info("Elasticsearch shop shadow mismatch: legacyIds={}, searchIds={}", legacyIds, searchIds);
            }
        }
        return legacyItems;
    }

    private List<ShopDiscoveryItemDTO> discoverLegacy(ShopDiscoveryQuery query) {
        return query.hasValidPoint() ? discoverByPoint(query) : discoverByPage(query);
    }

    private List<ShopDiscoveryItemDTO> toItems(ShopSearchProjectionService.SearchCandidates result) {
        return result.candidates().stream()
                .map(candidate -> toItem(candidate.shop(), candidate.distanceMeters())
                        .setSearchRank(candidate.searchRank()))
                .toList();
    }

    private List<ShopDiscoveryItemDTO> discoverByPoint(ShopDiscoveryQuery query) {
        return switch (geoProperties.resolveLocalDiscoveryMode()) {
            case MYSQL -> discoverBySql(query);
            case SHADOW -> discoverByShadow(query);
            case REDIS -> discoverByRedisWithSqlFallback(query);
        };
    }

    private List<ShopDiscoveryItemDTO> discoverByShadow(ShopDiscoveryQuery query) {
        List<ShopDiscoveryItemDTO> sqlItems = discoverBySql(query);
        try {
            RedisDiscoveryResult redisResult = discoverByRedis(query);
            if (!redisResult.ready()) {
                log.debug("Redis GEO shadow query skipped because the index key is not ready");
                return sqlItems;
            }
            List<Long> sqlIds = itemIds(sqlItems);
            List<Long> redisIds = itemIds(redisResult.items());
            if (!sqlIds.equals(redisIds)) {
                log.info("Redis GEO shadow mismatch: sqlIds={}, redisIds={}", sqlIds, redisIds);
            }
        } catch (RuntimeException exception) {
            log.warn("Redis GEO shadow query failed; SQL result remains authoritative", exception);
        }
        return sqlItems;
    }

    private List<ShopDiscoveryItemDTO> discoverByRedisWithSqlFallback(ShopDiscoveryQuery query) {
        try {
            RedisDiscoveryResult result = discoverByRedis(query);
            if (result.ready()) {
                return result.items();
            }
            log.debug("Redis GEO index key is not ready; falling back to SQL geo discovery");
        } catch (RuntimeException exception) {
            log.warn("Redis GEO discovery failed; falling back to SQL geo discovery", exception);
        }
        return discoverBySql(query);
    }

    private RedisDiscoveryResult discoverByRedis(ShopDiscoveryQuery query) {
        int maxCandidates = geoProperties.resolveRedisMaxCandidates();
        int targetLimit = query.resolveCandidateLimit(geoProperties);
        int candidateLimit = multiplyAndClamp(
                targetLimit,
                geoProperties.resolveRedisCandidateMultiplier(),
                maxCandidates
        );
        double radius = resolveRadiusMeters();

        while (true) {
            RedisShopGeoSearchService.SearchResult searchResult = redisGeoSearchService.search(
                    query.getTypeId(),
                    query.getX(),
                    query.getY(),
                    radius,
                    candidateLimit
            );
            if (!searchResult.ready()) {
                return RedisDiscoveryResult.notReady();
            }

            List<ShopDiscoveryItemDTO> items = hydrateRedisCandidates(query, searchResult.candidates(), radius);
            if (items.size() >= targetLimit
                    || searchResult.candidates().size() < candidateLimit
                    || candidateLimit >= maxCandidates) {
                return RedisDiscoveryResult.ready(items.stream().limit(targetLimit).toList());
            }

            candidateLimit = multiplyAndClamp(candidateLimit, 2, maxCandidates);
        }
    }

    private List<ShopDiscoveryItemDTO> hydrateRedisCandidates(
            ShopDiscoveryQuery query,
            List<RedisShopGeoSearchService.Candidate> candidates,
            double radius
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> ids = candidates.stream()
                .map(RedisShopGeoSearchService.Candidate::shopId)
                .distinct()
                .toList();
        QueryWrapper<Shop> wrapper = verifiedQuery(query)
                .in("id", ids);
        Map<Long, Shop> shopsById = shopService.list(wrapper).stream()
                .filter(shop -> shop.getId() != null)
                .collect(Collectors.toMap(Shop::getId, Function.identity(), (left, right) -> left));

        List<ShopDiscoveryItemDTO> items = new ArrayList<>(candidates.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (RedisShopGeoSearchService.Candidate candidate : candidates) {
            Shop shop = shopsById.get(candidate.shopId());
            if (shop == null
                    || !seen.add(candidate.shopId())
                    || !DiscoveryGeoUtils.isValidCoordinate(shop.getX(), shop.getY())) {
                continue;
            }
            double authoritativeDistance = DiscoveryGeoUtils.distanceMeters(
                    query.getX(),
                    query.getY(),
                    shop.getX(),
                    shop.getY()
            );
            if (authoritativeDistance <= radius) {
                items.add(toItem(shop, authoritativeDistance));
            }
        }
        return items.stream()
                .sorted(preferenceComparator(query))
                .toList();
    }

    private List<ShopDiscoveryItemDTO> discoverBySql(ShopDiscoveryQuery query) {
        double radius = resolveRadiusMeters();
        BoundingBox box = BoundingBox.around(query.getX(), query.getY(), radius);
        QueryWrapper<Shop> wrapper = verifiedQuery(query)
                .between("y", box.minLatitude(), box.maxLatitude());
        if (!box.fullLongitude()) {
            if (box.crossesAntimeridian()) {
                wrapper.and(longitude -> longitude
                        .ge("x", box.minLongitude())
                        .or()
                        .le("x", box.maxLongitude()));
            } else {
                wrapper.between("x", box.minLongitude(), box.maxLongitude());
            }
        }

        return shopService.list(wrapper).stream()
                .filter(shop -> DiscoveryGeoUtils.isValidCoordinate(shop.getX(), shop.getY()))
                .map(shop -> toItem(
                        shop,
                        DiscoveryGeoUtils.distanceMeters(query.getX(), query.getY(), shop.getX(), shop.getY())
                ))
                .filter(item -> item.getDistance() != null && item.getDistance() <= radius)
                .sorted(preferenceComparator(query))
                .limit(resolveGeoFetchLimit(query))
                .toList();
    }

    private QueryWrapper<Shop> verifiedQuery(ShopDiscoveryQuery query) {
        QueryWrapper<Shop> wrapper = new QueryWrapper<Shop>()
                .eq("geo_status", "verified")
                .isNotNull("x")
                .isNotNull("y");
        if (query.getTypeId() != null) {
            wrapper.eq("type_id", query.getTypeId());
        }
        if (StrUtil.isNotBlank(query.getKeyword())) {
            wrapper.like("name", query.normalizedKeyword());
        }
        return wrapper;
    }

    private List<ShopDiscoveryItemDTO> discoverByPage(ShopDiscoveryQuery query) {
        Page<Shop> page = baseQuery(query).page(new Page<>(1, query.resolveCandidateLimit(geoProperties)));
        return page.getRecords().stream()
                .map(shop -> toItem(shop, null))
                .sorted(preferenceComparator(query))
                .toList();
    }

    private Comparator<ShopDiscoveryItemDTO> preferenceComparator(ShopDiscoveryQuery query) {
        return Comparator
                .comparingInt((ShopDiscoveryItemDTO item) -> preferredCategoryWeight(query, item))
                .thenComparingDouble(item -> Objects.requireNonNullElse(item.getDistance(), Double.MAX_VALUE));
    }

    private int preferredCategoryWeight(ShopDiscoveryQuery query, ShopDiscoveryItemDTO item) {
        if (query == null || !query.hasPreferredCategory() || item == null) {
            return 0;
        }
        String category = DiscoveryGeoUtils.normalize(query.normalizedPreferredCategory());
        if (StrUtil.isBlank(category)) {
            return 0;
        }
        return containsNormalized(item.getName(), category) ? 0 : 1;
    }

    private boolean containsNormalized(String value, String expected) {
        return DiscoveryGeoUtils.normalize(value).contains(expected);
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
        double resolved = Objects.requireNonNullElse(radius, 5000D);
        return resolved > 0D ? resolved : 5000D;
    }

    private int resolveGeoFetchLimit(ShopDiscoveryQuery query) {
        return query.resolveCandidateLimit(geoProperties);
    }

    private int multiplyAndClamp(int value, int multiplier, int maximum) {
        long multiplied = (long) value * multiplier;
        return (int) Math.min(maximum, Math.max(value, multiplied));
    }

    private List<Long> itemIds(List<ShopDiscoveryItemDTO> items) {
        return items.stream()
                .map(ShopDiscoveryItemDTO::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private record RedisDiscoveryResult(boolean ready, List<ShopDiscoveryItemDTO> items) {

        private static RedisDiscoveryResult ready(List<ShopDiscoveryItemDTO> items) {
            return new RedisDiscoveryResult(true, items);
        }

        private static RedisDiscoveryResult notReady() {
            return new RedisDiscoveryResult(false, List.of());
        }
    }

    private record BoundingBox(
            double minLongitude,
            double maxLongitude,
            double minLatitude,
            double maxLatitude,
            boolean crossesAntimeridian,
            boolean fullLongitude
    ) {

        private static BoundingBox around(double x, double y, double radiusMeters) {
            double latitudeDelta = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS);
            double minLatitude = clampRedisLatitude(y - latitudeDelta);
            double maxLatitude = clampRedisLatitude(y + latitudeDelta);
            double cosine = Math.abs(Math.cos(Math.toRadians(y)));
            if (cosine < 1.0E-12D) {
                return new BoundingBox(-180D, 180D, minLatitude, maxLatitude, false, true);
            }

            double longitudeDelta = Math.toDegrees(radiusMeters / (EARTH_RADIUS_METERS * cosine));
            if (longitudeDelta >= 180D) {
                return new BoundingBox(-180D, 180D, minLatitude, maxLatitude, false, true);
            }

            double rawMin = x - longitudeDelta;
            double rawMax = x + longitudeDelta;
            boolean crossesAntimeridian = rawMin < -180D || rawMax > 180D;
            return new BoundingBox(
                    normalizeLongitude(rawMin),
                    normalizeLongitude(rawMax),
                    minLatitude,
                    maxLatitude,
                    crossesAntimeridian,
                    false
            );
        }

        private static double normalizeLongitude(double longitude) {
            double normalized = longitude % 360D;
            if (normalized < -180D) {
                normalized += 360D;
            } else if (normalized > 180D) {
                normalized -= 360D;
            }
            return normalized;
        }

        private static double clampRedisLatitude(double latitude) {
            return Math.max(-85.05112878D, Math.min(85.05112878D, latitude));
        }
    }
}
