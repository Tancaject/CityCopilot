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
 * Reads the Redis GEO projection only. MySQL remains the source of truth for
 * shop attributes and eligibility.
 */
@Service
public class RedisShopGeoSearchService {

    private final StringRedisTemplate redisTemplate;
    private final ShopGeoIndexService shopGeoIndexService;

    public RedisShopGeoSearchService(
            StringRedisTemplate redisTemplate,
            ShopGeoIndexService shopGeoIndexService
    ) {
        this.redisTemplate = redisTemplate;
        this.shopGeoIndexService = shopGeoIndexService;
    }

    public SearchResult search(
            Integer typeId,
            double x,
            double y,
            double radiusMeters,
            int limit
    ) {
        String key = typeId == null ? SHOP_GEO_ALL_KEY : SHOP_GEO_KEY + typeId;
        String generation = shopGeoIndexService.readyGeneration();
        if (generation == null) {
            return SearchResult.notReady();
        }

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(radiusMeters / 1000D, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(Math.max(1, limit))
        );
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

    private Long parseShopId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Candidate(Long shopId, double distanceMeters) {
    }

    public record SearchResult(boolean ready, List<Candidate> candidates) {

        public SearchResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static SearchResult ready(List<Candidate> candidates) {
            return new SearchResult(true, candidates);
        }

        public static SearchResult notReady() {
            return new SearchResult(false, List.of());
        }
    }
}
