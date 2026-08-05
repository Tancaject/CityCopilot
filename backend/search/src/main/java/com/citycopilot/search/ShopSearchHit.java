package com.citycopilot.search;

import java.util.Objects;

public record ShopSearchHit(ShopSearchDocument document, double relevanceScore) {

    public ShopSearchHit {
        Objects.requireNonNull(document, "document must not be null");
    }
}
