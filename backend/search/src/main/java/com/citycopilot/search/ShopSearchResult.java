package com.citycopilot.search;

import java.util.List;
import java.util.Objects;

/**
 * Search result with explicit availability semantics. An available empty result is not a failure.
 */
public record ShopSearchResult(
        Availability availability,
        List<ShopSearchHit> hits,
        long total,
        String unavailableReason) {

    public static final String SEARCH_DISABLED = "SEARCH_DISABLED";
    public static final String INDEX_NOT_READY = "INDEX_NOT_READY";
    public static final String INDEX_STALE = "INDEX_STALE";
    public static final String SEARCH_UNAVAILABLE = "SEARCH_UNAVAILABLE";

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }

    public ShopSearchResult {
        Objects.requireNonNull(availability, "availability must not be null");
        hits = hits == null ? List.of() : List.copyOf(hits);
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        if (availability == Availability.AVAILABLE && unavailableReason != null) {
            throw new IllegalArgumentException("available result must not have an unavailable reason");
        }
        if (availability == Availability.UNAVAILABLE && (unavailableReason == null || unavailableReason.isBlank())) {
            throw new IllegalArgumentException("unavailable result must have a reason");
        }
        if (availability == Availability.UNAVAILABLE && (!hits.isEmpty() || total != 0)) {
            throw new IllegalArgumentException("unavailable result must not contain search data");
        }
    }

    public boolean available() {
        return availability == Availability.AVAILABLE;
    }

    public static ShopSearchResult available(List<ShopSearchHit> hits, long total) {
        return new ShopSearchResult(Availability.AVAILABLE, hits, total, null);
    }

    public static ShopSearchResult empty() {
        return available(List.of(), 0);
    }

    public static ShopSearchResult unavailable(String reason) {
        return new ShopSearchResult(Availability.UNAVAILABLE, List.of(), 0, reason);
    }
}
