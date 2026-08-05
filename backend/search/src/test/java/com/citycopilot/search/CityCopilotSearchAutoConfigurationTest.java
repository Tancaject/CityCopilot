package com.citycopilot.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
}
