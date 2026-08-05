package com.citycopilot.search;

import java.util.Collection;

final class DisabledShopSearchGateway implements ShopSearchGateway {

    @Override
    public ShopSearchResult search(ShopSearchRequest request) {
        return ShopSearchResult.unavailable(ShopSearchResult.SEARCH_DISABLED);
    }

    @Override
    public void upsert(ShopSearchDocument document) {
        // Search is an optional projection. Disabled mode deliberately leaves the source of truth untouched.
    }

    @Override
    public void rebuild(Collection<ShopSearchDocument> documents) {
        // Search is an optional projection. Disabled mode deliberately leaves the source of truth untouched.
    }

    @Override
    public void rebuild(Collection<ShopSearchDocument> documents, RebuildCutover cutover) {
        // Search is an optional projection. Disabled mode deliberately leaves the source of truth untouched.
    }

    @Override
    public boolean isReady() {
        return false;
    }
}
