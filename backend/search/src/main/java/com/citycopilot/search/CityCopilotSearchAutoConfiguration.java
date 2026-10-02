package com.citycopilot.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Search 模块的 Spring Boot 自动配置入口。
 *
 * <p>它根据开关与 {@link ElasticsearchClient} 是否存在，注入真实、禁用或不可用三种
 * {@link ShopSearchGateway}，让 Core 始终可以依赖同一搜索边界。</p>
 */
@AutoConfiguration(afterName = "org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration")
@EnableConfigurationProperties(CityCopilotSearchProperties.class)
public class CityCopilotSearchAutoConfiguration {

    /**
     * 在搜索默认关闭时提供无操作实现，查询会明确返回“已禁用”。
     *
     * @return 禁用态搜索网关
     */
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

    /**
     * 搜索已开启且客户端可用时装配 Elasticsearch 网关。
     *
     * @param client Spring Boot 创建的官方 Elasticsearch 客户端
     * @param properties Search 模块配置
     * @return 可执行检索和索引投影的网关
     */
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

    /**
     * 搜索已开启但客户端缺失时提供显式失败实现，防止投影写入被静默丢弃。
     *
     * @return 不可用态搜索网关
     */
    @Bean
    @ConditionalOnMissingBean({ShopSearchGateway.class, ElasticsearchClient.class})
    @ConditionalOnProperty(prefix = "citycopilot.search", name = "enabled", havingValue = "true")
    ShopSearchGateway unavailableShopSearchGateway() {
        return new UnavailableShopSearchGateway();
    }
}
