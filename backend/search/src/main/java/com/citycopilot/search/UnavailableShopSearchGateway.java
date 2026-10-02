package com.citycopilot.search;

import java.util.Collection;

/**
 * 搜索已开启但 Elasticsearch 客户端缺失时的失败实现。
 *
 * <p>它保持依赖可注入，同时让投影写入和重建显式失败，避免调用方误以为索引已经同步。</p>
 */
final class UnavailableShopSearchGateway implements ShopSearchGateway {

    @Override
    public ShopSearchResult search(ShopSearchRequest request) {
        return ShopSearchResult.unavailable(ShopSearchResult.SEARCH_UNAVAILABLE);
    }

    @Override
    public void upsert(ShopSearchDocument document) {
        throw unavailable();
    }

    @Override
    public void rebuild(Collection<ShopSearchDocument> documents) {
        throw unavailable();
    }

    @Override
    public void rebuild(Collection<ShopSearchDocument> documents, RebuildCutover cutover) {
        throw unavailable();
    }

    @Override
    public boolean isReady() {
        return false;
    }

    /** @return 描述 Elasticsearch 客户端缺失的统一异常 */
    private ShopSearchException unavailable() {
        return new ShopSearchException(
                "Elasticsearch client is unavailable",
                new IllegalStateException("No ElasticsearchClient bean"));
    }
}
