package com.citycopilot.search;

import java.util.Collection;

/**
 * Keeps the optional search boundary injectable when enabled mode has no client,
 * while making projection write loss explicit to reconciliation callers.
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

    private ShopSearchException unavailable() {
        return new ShopSearchException(
                "Elasticsearch client is unavailable",
                new IllegalStateException("No ElasticsearchClient bean"));
    }
}
