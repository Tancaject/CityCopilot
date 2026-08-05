package com.citycopilot.search;

import co.elastic.clients.elasticsearch.core.SearchRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class ElasticsearchShopSearchGateway implements ShopSearchGateway {

    private static final DateTimeFormatter GENERATION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ShopSearchClient client;
    private final CityCopilotSearchProperties properties;
    private final ShopSearchRequestFactory requestFactory;
    private final Supplier<String> generationName;
    private final AtomicBoolean stale = new AtomicBoolean();

    ElasticsearchShopSearchGateway(ShopSearchClient client, CityCopilotSearchProperties properties) {
        this(client, properties, new ShopSearchRequestFactory(), defaultGenerationName(properties, Clock.systemUTC()));
    }

    ElasticsearchShopSearchGateway(
            ShopSearchClient client,
            CityCopilotSearchProperties properties,
            ShopSearchRequestFactory requestFactory,
            Supplier<String> generationName) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
        this.generationName = Objects.requireNonNull(generationName, "generationName must not be null");
    }

    @Override
    public ShopSearchResult search(ShopSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (stale.get()) {
            return ShopSearchResult.unavailable(ShopSearchResult.INDEX_STALE);
        }
        try {
            if (!client.aliasExists(properties.getIndexAlias())) {
                return ShopSearchResult.unavailable(ShopSearchResult.INDEX_NOT_READY);
            }
            SearchRequest searchRequest = requestFactory.create(
                    properties.getIndexAlias(), request, properties.getMaxPageSize());
            ShopSearchClient.SearchPage page = client.search(searchRequest);
            List<ShopSearchHit> hits = page.hits().stream()
                    .map(hit -> new ShopSearchHit(hit.document().toApiDocument(), hit.relevanceScore()))
                    .toList();
            return ShopSearchResult.available(hits, page.total());
        } catch (RuntimeException | IOException exception) {
            return ShopSearchResult.unavailable(ShopSearchResult.SEARCH_UNAVAILABLE);
        }
    }

    @Override
    public void upsert(ShopSearchDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        try {
            client.index(properties.getIndexAlias(), ElasticsearchShopDocument.from(document));
            stale.set(false);
        } catch (RuntimeException | IOException exception) {
            stale.set(true);
            throw new ShopSearchException("Failed to upsert shop search projection", exception);
        }
    }

    @Override
    public void rebuild(Collection<ShopSearchDocument> documents) {
        rebuild(documents, PendingGeneration::publish);
    }

    @Override
    public void rebuild(Collection<ShopSearchDocument> documents, RebuildCutover cutover) {
        Objects.requireNonNull(documents, "documents must not be null");
        Objects.requireNonNull(cutover, "cutover must not be null");
        String newIndex = generationName.get();
        try {
            client.createIndex(newIndex);
            bulkInto(newIndex, documents);
            AtomicBoolean published = new AtomicBoolean();
            cutover.execute(new PendingGeneration() {
                @Override
                public void upsertAll(Collection<ShopSearchDocument> catchUpDocuments) {
                    if (published.get()) {
                        throw new IllegalStateException("Cannot catch up a published search generation");
                    }
                    try {
                        bulkInto(newIndex, catchUpDocuments);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }

                @Override
                public void publish() {
                    if (!published.compareAndSet(false, true)) {
                        throw new IllegalStateException("Search generation was already published");
                    }
                    try {
                        client.refresh(newIndex);
                        client.switchAlias(
                                properties.getIndexAlias(),
                                properties.getIndexPrefix() + "-*",
                                newIndex);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }
            });
            if (!published.get()) {
                throw new IllegalStateException("Search rebuild cutover did not publish its generation");
            }
            stale.set(false);
        } catch (RuntimeException | IOException exception) {
            stale.set(true);
            throw new ShopSearchException("Failed to rebuild shop search projection", exception);
        }
    }

    @Override
    public boolean isReady() {
        if (stale.get()) {
            return false;
        }
        try {
            return client.aliasExists(properties.getIndexAlias());
        } catch (RuntimeException | IOException exception) {
            return false;
        }
    }

    @Override
    public boolean probeSharedReadiness() {
        try {
            boolean ready = client.aliasExists(properties.getIndexAlias());
            if (ready) {
                stale.set(false);
            }
            return ready;
        } catch (RuntimeException | IOException exception) {
            return false;
        }
    }

    private void bulkInto(String index, Collection<ShopSearchDocument> documents) throws IOException {
        List<ElasticsearchShopDocument> batch = new ArrayList<>(properties.getBulkBatchSize());
        for (ShopSearchDocument document : documents) {
            batch.add(ElasticsearchShopDocument.from(
                    Objects.requireNonNull(document, "document must not be null")));
            if (batch.size() == properties.getBulkBatchSize()) {
                client.bulkIndex(index, List.copyOf(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            client.bulkIndex(index, List.copyOf(batch));
        }
    }

    private static Supplier<String> defaultGenerationName(
            CityCopilotSearchProperties properties,
            Clock clock) {
        return () -> properties.getIndexPrefix()
                + "-" + GENERATION_TIME.format(Instant.now(clock))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
