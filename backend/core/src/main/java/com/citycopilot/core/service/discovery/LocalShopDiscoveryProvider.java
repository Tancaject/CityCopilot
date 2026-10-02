package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.config.ShopSearchProperties;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.utils.RedisGeoCoordinates;
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

/**
 * 本地可下单店铺发现数据源。
 *
 * <p>按配置在 Elasticsearch、Redis GEO 和 MySQL 之间选择主路径，并始终以 MySQL 校验店铺属性与资格。</p>
 */
@Slf4j
@Component
public class LocalShopDiscoveryProvider implements ShopDiscoveryProvider {

    /** SQL 包围盒计算采用的平均地球半径，单位为米。 */
    private static final double EARTH_RADIUS_METERS = 6371008.8D;

    /** MySQL 店铺事实源。 */
    private final ShopMapper shopMapper;

    /** 本地发现模式、半径和候选上限配置。 */
    private final GeoProperties geoProperties;

    /** Redis GEO 只读候选查询服务。 */
    private final RedisShopGeoSearchService redisGeoSearchService;

    /** 可选 Elasticsearch 店铺搜索投影。 */
    private final ShopSearchProjectionService searchProjectionService;

    /** 搜索引擎主读或影子比对模式配置。 */
    private final ShopSearchProperties searchProperties;

    /**
     * 生产构造器。
     *
     * @param shopMapper MySQL 店铺服务
     * @param geoProperties GEO 发现配置
     * @param redisGeoSearchService Redis GEO 查询服务
     * @param searchProjectionService 搜索投影服务
     * @param searchProperties 搜索模式配置
     */
    @Autowired
    public LocalShopDiscoveryProvider(
            ShopMapper shopMapper,
            GeoProperties geoProperties,
            RedisShopGeoSearchService redisGeoSearchService,
            ShopSearchProjectionService searchProjectionService,
            ShopSearchProperties searchProperties
    ) {
        this.shopMapper = shopMapper;
        this.geoProperties = geoProperties;
        this.redisGeoSearchService = redisGeoSearchService;
        this.searchProjectionService = searchProjectionService;
        this.searchProperties = searchProperties;
    }

    /**
     * 不启用搜索投影的兼容构造器，主要供旧调用链和测试使用。
     *
     * @param shopMapper MySQL 店铺服务
     * @param geoProperties GEO 发现配置
     * @param redisGeoSearchService Redis GEO 查询服务
     */
    public LocalShopDiscoveryProvider(
            ShopMapper shopMapper,
            GeoProperties geoProperties,
            RedisShopGeoSearchService redisGeoSearchService
    ) {
        this(shopMapper, geoProperties, redisGeoSearchService, null, new ShopSearchProperties());
    }

    @Override
    public List<ShopDiscoveryItemDTO> discover(ShopDiscoveryQuery query) {
        ShopDiscoveryQuery actualQuery = query == null ? new ShopDiscoveryQuery() : query;
        if (searchProjectionService == null || !searchProjectionService.shouldSearch(actualQuery)) {
            return discoverLegacy(actualQuery);
        }

        if (searchProperties.resolveMode() == ShopSearchProperties.Mode.ELASTICSEARCH) {
            // 搜索主读模式下若投影未就绪或漂移，立即退回 Redis GEO/MySQL 链路。
            ShopSearchProjectionService.SearchCandidates searchResult = searchProjectionService.search(actualQuery);
            return searchResult.available() ? toItems(searchResult) : discoverLegacy(actualQuery);
        }

        // 影子模式不改变用户结果，只比较搜索投影与旧链路的主键顺序以观察差异。
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

    /**
     * 选择未启用全文搜索时的本地发现路径。
     *
     * @param query 查询条件
     * @return 有坐标时走 GEO，其他场景走普通分页
     */
    private List<ShopDiscoveryItemDTO> discoverLegacy(ShopDiscoveryQuery query) {
        return query.hasValidPoint() ? discoverByPoint(query) : discoverByPage(query);
    }

    /**
     * 将已由 MySQL 权威补水的搜索候选转为统一 DTO。
     *
     * @param result 搜索投影候选
     * @return 保留搜索排名的发现 DTO
     */
    private List<ShopDiscoveryItemDTO> toItems(ShopSearchProjectionService.SearchCandidates result) {
        return result.candidates().stream()
                .map(candidate -> toItem(candidate.shop(), candidate.distanceMeters())
                        .setSearchRank(candidate.searchRank()))
                .toList();
    }

    /**
     * 按配置选择坐标发现模式。
     *
     * @param query 带合法坐标的查询
     * @return 本地候选
     */
    private List<ShopDiscoveryItemDTO> discoverByPoint(ShopDiscoveryQuery query) {
        return switch (geoProperties.resolveLocalDiscoveryMode()) {
            case MYSQL -> discoverBySql(query);
            case SHADOW -> discoverByShadow(query);
            case REDIS -> discoverByRedisWithSqlFallback(query);
        };
    }

    /**
     * 使用 SQL 作为真实响应，同时影子执行 Redis GEO 并记录主键顺序差异。
     *
     * @param query 坐标查询
     * @return SQL 权威结果
     */
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

    /**
     * Redis GEO 主读路径；索引未就绪或调用异常时退回 SQL。
     *
     * @param query 坐标查询
     * @return 可用的本地候选
     */
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

    /**
     * 渐进扩大 Redis 候选窗口，弥补权威补水过滤掉失效成员后的数量缺口。
     *
     * @param query 坐标查询
     * @return Redis 是否就绪及完成补水的候选
     */
    private RedisDiscoveryResult discoverByRedis(ShopDiscoveryQuery query) {
        int maxCandidates = geoProperties.resolveRedisMaxCandidates();
        int targetLimit = query.resolveCandidateLimit(geoProperties);
        int candidateLimit = multiplyAndClamp(
                targetLimit,
                geoProperties.resolveRedisCandidateMultiplier(),
                maxCandidates
        );
        double radius = resolveRadiusMeters(query);

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

            // GEO 只保证空间候选；必须回 MySQL 过滤状态、分类和关键词。
            List<ShopDiscoveryItemDTO> items = hydrateRedisCandidates(query, searchResult.candidates(), radius);
            if (items.size() >= targetLimit
                    || searchResult.candidates().size() < candidateLimit
                    || candidateLimit >= maxCandidates) {
                return RedisDiscoveryResult.ready(items.stream().limit(targetLimit).toList());
            }

            candidateLimit = multiplyAndClamp(candidateLimit, 2, maxCandidates);
        }
    }

    /**
     * 从 MySQL 批量补齐 Redis GEO 候选，并重新计算权威距离和资格。
     *
     * @param query 原始发现条件
     * @param candidates Redis 返回的店铺主键与近似距离
     * @param radius 允许半径，单位为米
     * @return 去重、过滤并按偏好排序的本地店铺
     */
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
        Map<Long, Shop> shopsById = shopMapper.selectList(wrapper).stream()
                .filter(shop -> shop.getId() != null)
                .collect(Collectors.toMap(Shop::getId, Function.identity(), (left, right) -> left));

        List<ShopDiscoveryItemDTO> items = new ArrayList<>(candidates.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (RedisShopGeoSearchService.Candidate candidate : candidates) {
            Shop shop = shopsById.get(candidate.shopId());
            if (shop == null
                    || !seen.add(candidate.shopId())
                    || !RedisGeoCoordinates.isValid(shop.getX(), shop.getY())) {
                continue;
            }
            // 使用 MySQL 坐标重算距离，避免旧 GEO 投影把范围外店铺误放进结果。
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

    /**
     * 通过经纬度包围盒缩小 MySQL 候选，再用球面距离做精确半径过滤。
     *
     * @param query 带合法坐标的查询
     * @return 距离与偏好排序后的候选
     */
    private List<ShopDiscoveryItemDTO> discoverBySql(ShopDiscoveryQuery query) {
        double radius = resolveRadiusMeters(query);
        BoundingBox box = BoundingBox.around(query.getX(), query.getY(), radius);
        QueryWrapper<Shop> wrapper = verifiedQuery(query)
                .between("y", box.minLatitude(), box.maxLatitude());
        // 跨越 180 度经线时，经度条件必须拆成两段 OR，普通 BETWEEN 会漏掉候选。
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

        return shopMapper.selectList(wrapper).stream()
                .filter(shop -> RedisGeoCoordinates.isValid(shop.getX(), shop.getY()))
                .map(shop -> toItem(
                        shop,
                        DiscoveryGeoUtils.distanceMeters(query.getX(), query.getY(), shop.getX(), shop.getY())
                ))
                .filter(item -> item.getDistance() != null && item.getDistance() <= radius)
                .sorted(preferenceComparator(query))
                .limit(resolveGeoFetchLimit(query))
                .toList();
    }

    /**
     * 构造所有坐标主路径共享的 MySQL 权威资格条件。
     *
     * @param query 分类和关键词过滤条件
     * @return 仅含已核验且坐标完整店铺的查询器
     */
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

    /**
     * 无坐标时按数据库分页获取候选，供分类或关键词浏览使用。
     *
     * @param query 查询条件
     * @return 本地店铺候选
     */
    private List<ShopDiscoveryItemDTO> discoverByPage(ShopDiscoveryQuery query) {
        Page<Shop> page = baseQuery(query).page(new Page<>(1, query.resolveCandidateLimit(geoProperties)));
        return page.getRecords().stream()
                .map(shop -> toItem(shop, null))
                .sorted(preferenceComparator(query))
                .toList();
    }

    /**
     * 构建偏好排序器；偏好只提升名称命中的店铺，不过滤其他候选。
     *
     * @param query 含可选偏好分类的查询
     * @return 偏好优先、距离次之的比较器
     */
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
        QueryChainWrapper<Shop> wrapper = new QueryChainWrapper<>(shopMapper);
        if (query.getTypeId() != null) {
            wrapper.eq("type_id", query.getTypeId());
        }
        if (StrUtil.isNotBlank(query.getKeyword())) {
            wrapper.like("name", query.normalizedKeyword());
        }
        return wrapper;
    }

    /**
     * 将 MySQL 店铺实体转换为可下单的统一发现 DTO。
     *
     * @param shop 权威店铺数据
     * @param distance 可选距离，单位为米
     * @return 本地发现候选
     */
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

    private double resolveRadiusMeters(ShopDiscoveryQuery query) {
        if (query.getRadiusMeters() != null) return query.getRadiusMeters();
        Double radius = geoProperties == null ? null : geoProperties.getNearbyRadiusMeters();
        double resolved = Objects.requireNonNullElse(radius, 5000D);
        return resolved > 0D ? resolved : 5000D;
    }

    private int resolveGeoFetchLimit(ShopDiscoveryQuery query) {
        return query.resolveCandidateLimit(geoProperties);
    }

    /**
     * 安全放大候选窗口且不超过配置上限。
     *
     * @param value 当前窗口
     * @param multiplier 放大倍数
     * @param maximum 最大窗口
     * @return 下一次窗口大小
     */
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

    /**
     * Redis GEO 发现的内部结果。
     *
     * @param ready 查询是否基于稳定已发布代际
     * @param items 已经 MySQL 权威补水的候选
     */
    private record RedisDiscoveryResult(boolean ready, List<ShopDiscoveryItemDTO> items) {

        private static RedisDiscoveryResult ready(List<ShopDiscoveryItemDTO> items) {
            return new RedisDiscoveryResult(true, items);
        }

        private static RedisDiscoveryResult notReady() {
            return new RedisDiscoveryResult(false, List.of());
        }
    }

    /**
     * SQL 粗筛使用的经纬度包围盒。
     *
     * @param minLongitude 最小经度
     * @param maxLongitude 最大经度
     * @param minLatitude 最小纬度
     * @param maxLatitude 最大纬度
     * @param crossesAntimeridian 是否跨越 180 度经线
     * @param fullLongitude 是否覆盖全部经度
     */
    private record BoundingBox(
            double minLongitude,
            double maxLongitude,
            double minLatitude,
            double maxLatitude,
            boolean crossesAntimeridian,
            boolean fullLongitude
    ) {

        /**
         * 按中心点和半径计算包围盒，并处理极区与跨日期变更线场景。
         *
         * @param x 中心经度
         * @param y 中心纬度
         * @param radiusMeters 半径，单位为米
         * @return 可用于 SQL 粗筛的包围盒
         */
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
