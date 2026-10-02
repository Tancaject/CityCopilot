package com.citycopilot.search;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.DistanceUnit;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;

import java.util.List;

/** 将业务搜索条件翻译为 Elasticsearch Query DSL。 */
final class ShopSearchRequestFactory {

    /** 关键词检索字段及其相关度权重。 */
    private static final List<String> KEYWORD_FIELDS = List.of(
            "name^4", "name.standard^3", "typeName^3", "typeName.standard^2",
            "area^2", "area.standard^1.5", "address", "address.standard");
    /** 用户偏好分类用于软加权的字段及权重，不作为硬过滤条件。 */
    private static final List<String> CATEGORY_FIELDS = List.of(
            "typeName^6", "typeName.standard^5", "name^2", "name.standard");

    /**
     * 构造带文本相关度、硬过滤、GEO 距离和分页限制的查询。
     *
     * @param alias 稳定索引别名
     * @param request 业务搜索条件
     * @param maxPageSize 服务端允许的最大页大小
     * @return 官方客户端可执行的搜索请求
     */
    SearchRequest create(String alias, ShopSearchRequest request, int maxPageSize) {
        BoolQuery.Builder query = new BoolQuery.Builder();

        if (request.keyword() == null) {
            query.must(q -> q.matchAll(m -> m));
        } else {
            query.must(q -> q.multiMatch(m -> m
                    .query(request.keyword())
                    .fields(KEYWORD_FIELDS)));
        }

        if (request.preferredCategory() != null) {
            // should 子句只提高偏好分类的分数；minimumShouldMatch=0 保证它不会变成筛选条件。
            query.should(q -> q.multiMatch(m -> m
                    .query(request.preferredCategory())
                    .fields(CATEGORY_FIELDS)
                    .boost(2.0f)));
            query.minimumShouldMatch("0");
        }

        if (request.typeId() != null) {
            query.filter(q -> q.term(t -> t.field("typeId").value(request.typeId())));
        }
        if (request.minAvgPrice() != null || request.maxAvgPrice() != null) {
            query.filter(priceRange(request));
        }
        if (request.minScore() != null) {
            query.filter(q -> q.range(r -> r.longNumber(n -> n.field("score").gte(request.minScore().longValue()))));
        }
        if (request.radiusMeters() != null) {
            query.filter(q -> q.geoDistance(g -> g
                    .field("location")
                    .distance(request.radiusMeters() + "m")
                    .location(location -> location.latlon(latLon -> latLon
                            .lat(request.latitude())
                            .lon(request.longitude())))));
        }

        int pageSize = Math.min(request.size(), maxPageSize);
        SearchRequest.Builder search = new SearchRequest.Builder()
                .index(alias)
                .from(request.offset())
                .size(pageSize)
                .trackTotalHits(track -> track.enabled(true))
                .query(q -> q.bool(query.build()))
                .sort(sort -> sort.score(score -> score.order(SortOrder.Desc)));
        if (request.radiusMeters() != null) {
            search.sort(sort -> sort.geoDistance(distance -> distance
                    .field("location")
                    .location(location -> location.latlon(latLon -> latLon
                            .lat(request.latitude())
                            .lon(request.longitude())))
                    .order(SortOrder.Asc)
                    .unit(DistanceUnit.Meters)));
        }
        return search.build();
    }

    /** @return 仅包含请求中已提供上下界的价格区间条件 */
    private Query priceRange(ShopSearchRequest request) {
        return Query.of(q -> q.range(r -> r.longNumber(range -> {
            range.field("avgPrice");
            if (request.minAvgPrice() != null) {
                range.gte(request.minAvgPrice());
            }
            if (request.maxAvgPrice() != null) {
                range.lte(request.maxAvgPrice());
            }
            return range;
        })));
    }
}
