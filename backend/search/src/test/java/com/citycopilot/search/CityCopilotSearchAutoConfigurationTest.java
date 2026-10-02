package com.citycopilot.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CityCopilotSearchAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CityCopilotSearchAutoConfiguration.class));

    @Test
    void defaultsToDisabledGateway() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ShopSearchGateway.class);
            ShopSearchGateway gateway = context.getBean(ShopSearchGateway.class);
            assertThat(gateway).isInstanceOf(DisabledShopSearchGateway.class);
            assertThat(gateway.isReady()).isFalse();
            assertThat(gateway.search(ShopSearchRequest.builder().build()).unavailableReason())
                    .isEqualTo(ShopSearchResult.SEARCH_DISABLED);
        });
    }

    @Test
    void enabledModeExposesUnavailableGatewayWhenClientIsMissing() {
        contextRunner
                .withPropertyValues("citycopilot.search.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ShopSearchGateway.class);
                    ShopSearchResult result = context.getBean(ShopSearchGateway.class)
                            .search(ShopSearchRequest.builder().build());
                    assertThat(result.available()).isFalse();
                    assertThat(result.unavailableReason()).isEqualTo(ShopSearchResult.SEARCH_UNAVAILABLE);
                    assertThatThrownBy(() -> context.getBean(ShopSearchGateway.class).upsert(
                            ShopSearchDocument.builder().id(1L).name("test").build()))
                            .isInstanceOf(ShopSearchException.class);
                });
    }

    @Test
    void enabledModeUsesOfficialClientAndBindsIndexAlias() {
        contextRunner
                .withPropertyValues(
                        "citycopilot.search.enabled=true",
                        "citycopilot.search.index-alias=custom-shop-alias")
                .withBean(ElasticsearchClient.class, () -> mock(ElasticsearchClient.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(ShopSearchGateway.class);
                    assertThat(context.getBean(ShopSearchGateway.class))
                            .isInstanceOf(ElasticsearchShopSearchGateway.class);
                    assertThat(context.getBean(CityCopilotSearchProperties.class).getIndexAlias())
                            .isEqualTo("custom-shop-alias");
                });
    }

    @Test
    void bootFourCreatesJacksonThreeClientBeforeSelectingSearchGateway() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(
                        ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class))
                .withPropertyValues("citycopilot.search.enabled=true", "spring.elasticsearch.uris=http://127.0.0.1:1")
                .run(context -> {
                    assertThat(context).hasSingleBean(ElasticsearchClient.class);
                    assertThat(context.getBean(JsonpMapper.class)).isInstanceOf(Jackson3JsonpMapper.class);
                    assertThat(context).hasSingleBean(ShopSearchGateway.class);
                    assertThat(context.getBean(ShopSearchGateway.class))
                            .isInstanceOf(ElasticsearchShopSearchGateway.class);
                });
    }
}
