package com.citycopilot.core.service.discovery;

import com.citycopilot.core.service.ShopGeoIndexService;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_ALL_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * Redis GEO 投影只读查询服务。
 *
 * <p>这里仅返回店铺主键和距离；店铺属性与可用性仍需回到 MySQL 事实源校验。</p>
 */
@Service
public class RedisShopGeoSearchService {

    /** Redis GEO 查询客户端。 */
    private final StringRedisTemplate redisTemplate;

    /** GEO 索引生命周期服务，用于解析当前已发布代际。 */
    private final ShopGeoIndexService shopGeoIndexService;

    /**
     * @param redisTemplate Redis 客户端
     * @param shopGeoIndexService GEO 索引生命周期服务
     */
    public RedisShopGeoSearchService(
            StringRedisTemplate redisTemplate,
            ShopGeoIndexService shopGeoIndexService
    ) {
        this.redisTemplate = redisTemplate;
        this.shopGeoIndexService = shopGeoIndexService;
    }

    /**
     * 从当前已发布 GEO 代际中按距离升序查找候选。
     *
     * @param typeId 可选店铺分类；为空时查询全分类索引
     * @param x 中心经度
     * @param y 中心纬度
     * @param radiusMeters 搜索半径，单位为米
     * @param limit 最大候选数
     * @return 索引就绪状态及候选主键、距离
     */
    public SearchResult search(
            Integer typeId,
            double x,
            double y,
            double radiusMeters,
            int limit
    ) {
        String generation = shopGeoIndexService.readyGeneration();
        if (generation == null) {
            return SearchResult.notReady();
        }
        String key = generation.startsWith("v2:")
                ? shopGeoIndexService.keyForGeneration(
                        generation,
                        typeId == null ? null : typeId.longValue())
                : typeId == null ? SHOP_GEO_ALL_KEY : SHOP_GEO_KEY + typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(radiusMeters / 1000D, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(Math.max(1, limit))
        );
        // 查询期间若发布代际发生切换，则整批结果不可视为同一一致快照，交由上层降级。
        if (!generation.equals(shopGeoIndexService.readyGeneration())) {
            return SearchResult.notReady();
        }
        if (results == null || results.getContent().isEmpty()) {
            return SearchResult.ready(List.of());
        }

        List<Candidate> candidates = new ArrayList<>(results.getContent().size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            Long shopId = parseShopId(result.getContent().getName());
            Distance distance = result.getDistance();
            if (shopId != null && distance != null) {
                candidates.add(new Candidate(
                        shopId,
                        distance.in(Metrics.KILOMETERS).getValue() * 1000D
                ));
            }
        }
        return SearchResult.ready(candidates);
    }

    /**
     * 将 Redis GEO 成员解析为店铺主键；忽略索引中的损坏成员。
     *
     * @param value GEO 成员字符串
     * @return 店铺主键，格式无效时返回 {@code null}
     */
    private Long parseShopId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Redis GEO 候选。
     *
     * @param shopId 店铺主键
     * @param distanceMeters 到查询中心的距离，单位为米
     */
    public record Candidate(Long shopId, double distanceMeters) {
    }

    /**
     * 一次 GEO 查询结果。
     *
     * @param ready 查询前后均指向同一已发布代际
     * @param candidates 按距离升序排列的候选
     */
    public record SearchResult(boolean ready, List<Candidate> candidates) {

        public SearchResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /**
         * @param candidates 候选列表
         * @return 已就绪结果
         */
        public static SearchResult ready(List<Candidate> candidates) {
            return new SearchResult(true, candidates);
        }

        /** @return 提示上层改走 SQL 的未就绪结果 */
        public static SearchResult notReady() {
            return new SearchResult(false, List.of());
        }
    }
}
