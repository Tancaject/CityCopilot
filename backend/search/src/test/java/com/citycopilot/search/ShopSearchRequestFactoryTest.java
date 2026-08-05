package com.citycopilot.search;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ShopSearchRequestFactoryTest {

    private final ShopSearchRequestFactory factory = new ShopSearchRequestFactory();

    @Test
    void buildsKeywordPreferenceFiltersGeoAndPagination() {
        ShopSearchRequest criteria = ShopSearchRequest.builder()
                .keyword("咖啡 安静")
                .preferredCategory("咖啡馆")
                .typeId(7L)
                .minAvgPrice(20L)
                .maxAvgPrice(80L)
                .minScore(40)
                .longitude(121.4737)
                .latitude(31.2304)
                .radiusMeters(1_500.0)
                .offset(10)
                .size(500)
                .build();

        SearchRequest request = factory.create("shops", criteria, 100);
        BoolQuery query = request.query().bool();

        assertThat(request.index()).containsExactly("shops");
        assertThat(request.from()).isEqualTo(10);
        assertThat(request.size()).isEqualTo(100);
        assertThat(query.must()).singleElement().satisfies(keyword -> {
            assertThat(keyword.isMultiMatch()).isTrue();
            assertThat(keyword.multiMatch().query()).isEqualTo("咖啡 安静");
            assertThat(keyword.multiMatch().fields())
                    .containsExactly(
                            "name^4", "name.standard^3", "typeName^3", "typeName.standard^2",
                            "area^2", "area.standard^1.5", "address", "address.standard");
        });
        assertThat(query.should()).singleElement().satisfies(preference -> {
            assertThat(preference.isMultiMatch()).isTrue();
            assertThat(preference.multiMatch().query()).isEqualTo("咖啡馆");
            assertThat(preference.multiMatch().fields())
                    .containsExactly("typeName^6", "typeName.standard^5", "name^2", "name.standard");
        });
        assertThat(query.minimumShouldMatch()).isEqualTo("0");
        assertThat(query.filter()).hasSize(4);
        assertThat(query.filter()).extracting(Query::isTerm, Query::isRange, Query::isGeoDistance)
                .contains(tuple(true, false, false), tuple(false, true, false), tuple(false, false, true));
        assertThat(query.filter().stream().filter(Query::isGeoDistance).findFirst().orElseThrow()
                .geoDistance().distance()).isEqualTo("1500.0m");
        assertThat(request.sort()).hasSize(2);
        assertThat(request.sort().get(0).score().order()).isEqualTo(SortOrder.Desc);
        assertThat(request.sort().get(1).geoDistance().order()).isEqualTo(SortOrder.Asc);
    }

    @Test
    void usesMatchAllWhenOnlyPreferenceIsPresentSoPreferenceRemainsSoft() {
        SearchRequest request = factory.create("shops", ShopSearchRequest.builder()
                .preferredCategory("火锅")
                .build(), 100);

        BoolQuery query = request.query().bool();
        assertThat(query.must()).singleElement().satisfies(value -> assertThat(value.isMatchAll()).isTrue());
        assertThat(query.should()).hasSize(1);
        assertThat(query.minimumShouldMatch()).isEqualTo("0");
        assertThat(request.sort()).singleElement()
                .satisfies(sort -> assertThat(sort.score().order()).isEqualTo(SortOrder.Desc));
    }
}
