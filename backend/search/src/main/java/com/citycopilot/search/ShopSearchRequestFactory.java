package com.citycopilot.search;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.DistanceUnit;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonData;

import java.util.List;

final class ShopSearchRequestFactory {

    private static final List<String> KEYWORD_FIELDS = List.of(
            "name^4", "name.standard^3", "typeName^3", "typeName.standard^2",
            "area^2", "area.standard^1.5", "address", "address.standard");
    private static final List<String> CATEGORY_FIELDS = List.of(
            "typeName^6", "typeName.standard^5", "name^2", "name.standard");

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
            query.filter(q -> q.range(r -> r.field("score").gte(JsonData.of(request.minScore()))));
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

    private Query priceRange(ShopSearchRequest request) {
        return Query.of(q -> q.range(range -> {
            range.field("avgPrice");
            if (request.minAvgPrice() != null) {
                range.gte(JsonData.of(request.minAvgPrice()));
            }
            if (request.maxAvgPrice() != null) {
                range.lte(JsonData.of(request.maxAvgPrice()));
            }
            return range;
        }));
    }
}
