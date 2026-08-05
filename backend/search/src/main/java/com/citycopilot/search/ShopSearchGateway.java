package com.citycopilot.search;

import java.util.Collection;

/**
 * Search projection boundary for shop discovery.
 */
public interface ShopSearchGateway {

    ShopSearchResult search(ShopSearchRequest request);

    void upsert(ShopSearchDocument document);

    void rebuild(Collection<ShopSearchDocument> documents);

    /**
     * Builds an isolated generation, then delegates incremental catch-up and
     * publication to a caller that owns the source watermark barrier.
     */
    void rebuild(Collection<ShopSearchDocument> documents, RebuildCutover cutover);

    @FunctionalInterface
    interface RebuildCutover {
        void execute(PendingGeneration generation);
    }

    interface PendingGeneration {
        void upsertAll(Collection<ShopSearchDocument> documents);

        void publish();
    }

    /** Probes the shared alias and may clear replica-local transient state. */
    default boolean probeSharedReadiness() {
        return isReady();
    }

    boolean isReady();
}
