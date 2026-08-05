package com.citycopilot.search;

import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopSearchIndexDefinitionTest {

    @Test
    void definesPluginFreeChineseCompatibleNgramAndGeoMapping() {
        CreateIndexRequest request = ShopSearchIndexDefinition.create("shops-20260804");

        assertThat(request.index()).isEqualTo("shops-20260804");
        assertThat(ShopSearchIndexDefinition.INDEX_BODY)
                .contains("\"type\": \"ngram\"")
                .contains("\"min_gram\": 1")
                .contains("\"max_gram\": 2")
                .contains("\"search_analyzer\": \"standard\"")
                .contains("\"location\": {\"type\": \"geo_point\"}")
                .doesNotContain("ik_");
    }
}
