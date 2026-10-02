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
import java.util.regex.Pattern;

/**
 * 基于 Elasticsearch 的店铺搜索投影网关。
 *
 * <p>增量写入使用稳定别名；全量重建先写隔离物理索引、追平水位后的增量，再原子切换别名。
 * 任一投影写失败都会把本节点标记为 stale，避免继续返回可能过期的结果。</p>
 */
final class ElasticsearchShopSearchGateway implements ShopSearchGateway {

    /** 物理索引代际名称中的 UTC 时间格式。 */
    private static final DateTimeFormatter GENERATION_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    /** 隔离官方 Elasticsearch 客户端细节的内部端口。 */
    private final ShopSearchClient client;
    /** 索引别名、前缀和批大小配置。 */
    private final CityCopilotSearchProperties properties;
    /** 把业务条件转换为 Query DSL 的工厂。 */
    private final ShopSearchRequestFactory requestFactory;
    /** 生成唯一物理索引名；测试可注入确定性实现。 */
    private final Supplier<String> generationName;
    /** 记录本节点最近一次投影写入是否失败。 */
    private final AtomicBoolean stale = new AtomicBoolean();

    /**
     * @param client Elasticsearch 操作端口
     * @param properties Search 模块配置
     */
    ElasticsearchShopSearchGateway(ShopSearchClient client, CityCopilotSearchProperties properties) {
        this(client, properties, new ShopSearchRequestFactory(), defaultGenerationName(properties, Clock.systemUTC()));
    }

    /**
     * 完整构造器，额外允许测试替换查询工厂和索引名生成器。
     */
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
            // 先由工厂统一施加页大小上限，再通过客户端执行，避免控制器绕过搜索约束。
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
            // 新代际始终在隔离索引中构建；未完成前不会影响稳定别名上的线上读取。
            client.createIndex(newIndex);
            bulkInto(newIndex, documents);
            AtomicBoolean published = new AtomicBoolean();
            cutover.execute(new PendingGeneration() {
                private List<String> retired = List.of();
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
                        // refresh 保证全量和追平数据可见，随后再原子切换稳定别名。
                        client.refresh(newIndex);
                        List<String> previous = client.aliasTargets(properties.getIndexAlias());
                        client.switchAlias(
                                properties.getIndexAlias(),
                                properties.getIndexPrefix() + "-*",
                                newIndex);
                        retired = previous.stream().filter(ElasticsearchShopSearchGateway.this::isManagedGeneration)
                                .filter(index -> !index.equals(newIndex)).toList();
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }

                @Override
                public List<String> retiredGenerations() {
                    return retired;
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
    public CleanupDecision deleteRetiredGeneration(String generation) {
        if (!isManagedGeneration(generation)) {
            throw new IllegalArgumentException("Refuse to delete an index outside this search generation namespace");
        }
        try {
            List<String> active = client.aliasTargets(properties.getIndexAlias());
            if (active.isEmpty()) return CleanupDecision.RETAINED_UNREADY;
            if (active.contains(generation)) return CleanupDecision.RETAINED_ALIAS;
            return client.deleteUnaliasedIndex(generation)
                    ? CleanupDecision.REMOVED_OR_ABSENT : CleanupDecision.RETAINED_ALIAS;
        } catch (IOException failure) {
            throw new ShopSearchException("Failed to remove retired shop search generation " + generation, failure);
        }
    }

    private boolean isManagedGeneration(String index) {
        return index != null && index.matches("[a-z0-9][a-z0-9._-]*-[0-9]{17}-[0-9a-f]{8}")
                && index.matches(Pattern.quote(properties.getIndexPrefix()) + "-[0-9]{17}-[0-9a-f]{8}");
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

    /**
     * 分批写入一个物理索引，控制单次 bulk 请求大小。
     *
     * @param index 目标物理索引
     * @param documents 待写入投影
     */
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

    /**
     * @param properties 提供物理索引前缀
     * @param clock 提供可测试的当前时间
     * @return 由 UTC 时间和随机后缀组成的索引名生成器
     */
    private static Supplier<String> defaultGenerationName(
            CityCopilotSearchProperties properties,
            Clock clock) {
        return () -> properties.getIndexPrefix()
                + "-" + GENERATION_TIME.format(Instant.now(clock))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
