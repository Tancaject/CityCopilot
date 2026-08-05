package com.citycopilot.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration")
@EnableConfigurationProperties(CityCopilotSearchProperties.class)
public class CityCopilotSearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ShopSearchGateway.class)
    @ConditionalOnProperty(
            prefix = "citycopilot.search",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    ShopSearchGateway disabledShopSearchGateway() {
        return new DisabledShopSearchGateway();
    }

    @Bean
    @ConditionalOnMissingBean(ShopSearchGateway.class)
    @ConditionalOnBean(ElasticsearchClient.class)
    @ConditionalOnProperty(prefix = "citycopilot.search", name = "enabled", havingValue = "true")
    ShopSearchGateway elasticsearchShopSearchGateway(
            ElasticsearchClient client,
            CityCopilotSearchProperties properties) {
        return new ElasticsearchShopSearchGateway(
                new OfficialElasticsearchShopSearchClient(client), properties);
    }

    @Bean
    @ConditionalOnMissingBean({ShopSearchGateway.class, ElasticsearchClient.class})
    @ConditionalOnProperty(prefix = "citycopilot.search", name = "enabled", havingValue = "true")
    ShopSearchGateway unavailableShopSearchGateway() {
        return new UnavailableShopSearchGateway();
    }
}
