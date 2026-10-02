package com.citycopilot.search;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchShopDocumentTest {

    private final ObjectMapper objectMapper = JsonMapper.builderWithJackson2Defaults().findAndAddModules().build();

    @Test
    void jacksonRoundTripPreservesGeoPointAndApiFields() throws Exception {
        ShopSearchDocument source = ShopSearchDocument.builder()
                .id(7L)
                .name("西湖咖啡")
                .typeId(3L)
                .typeName("咖啡馆")
                .longitude(120.15D)
                .latitude(30.27D)
                .updateTime(LocalDateTime.of(2026, 8, 4, 12, 30))
                .projectionVersion(12L)
                .build();

        ElasticsearchShopDocument indexed = ElasticsearchShopDocument.from(source);
        String json = objectMapper.writeValueAsString(indexed);
        ElasticsearchShopDocument restored = objectMapper.readValue(json, ElasticsearchShopDocument.class);

        assertThat(json).contains("\"location\":{\"lat\":30.27,\"lon\":120.15}");
        assertThat(json).contains("\"projectionVersion\":12");
        assertThat(restored.toApiDocument()).isEqualTo(source);
    }
}
