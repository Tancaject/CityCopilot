package com.citycopilot.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 使用 Elasticsearch 官方 Java 客户端实现 {@link ShopSearchClient} 的基础设施适配器。 */
final class OfficialElasticsearchShopSearchClient implements ShopSearchClient {

    /** 真正发送索引、搜索与别名请求的官方客户端。 */
    private final ElasticsearchClient client;

    OfficialElasticsearchShopSearchClient(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public boolean aliasExists(String alias) throws IOException {
        return client.indices().existsAlias(request -> request.name(alias)).value();
    }

    @Override
    public SearchPage search(SearchRequest request) throws IOException {
        SearchResponse<ElasticsearchShopDocument> response =
                client.search(request, ElasticsearchShopDocument.class);
        List<SearchEntry> hits = new ArrayList<>(response.hits().hits().size());
        for (Hit<ElasticsearchShopDocument> hit : response.hits().hits()) {
            if (hit.source() != null) {
                hits.add(new SearchEntry(hit.source(), hit.score() == null ? 0.0 : hit.score()));
            }
        }
        long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
        return new SearchPage(hits, total);
    }

    @Override
    public void index(String alias, ElasticsearchShopDocument document) throws IOException {
        // requireAlias 防止配置错误时把稳定别名误当作新物理索引自动创建。
        // ExternalGte 让 MySQL 投影版本决定新旧顺序，重放旧消息不会覆盖新文档。
        client.index(request -> request
                .index(alias)
                .id(document.id().toString())
                .requireAlias(true)
                .version(document.projectionVersion())
                .versionType(VersionType.ExternalGte)
                .document(document));
    }

    @Override
    public void createIndex(String index) throws IOException {
        client.indices().create(ShopSearchIndexDefinition.create(index));
    }

    @Override
    public void bulkIndex(String index, List<ElasticsearchShopDocument> documents) throws IOException {
        BulkRequest.Builder request = new BulkRequest.Builder().index(index);
        for (ElasticsearchShopDocument document : documents) {
            request.operations(operation -> operation.index(item -> item
                    .id(document.id().toString())
                    .version(document.projectionVersion())
                    .versionType(VersionType.ExternalGte)
                    .document(document)));
        }
        BulkResponse response = client.bulk(request.build());
        if (response.errors()) {
            // bulk API 可能整体返回成功但部分条目失败，必须检查条目结果再允许发布索引。
            String reason = response.items().stream()
                    .filter(item -> item.error() != null)
                    .findFirst()
                    .map(item -> item.error().reason())
                    .orElse("unknown bulk indexing error");
            throw new IOException("Elasticsearch bulk indexing failed: " + reason);
        }
    }

    @Override
    public void refresh(String index) throws IOException {
        client.indices().refresh(request -> request.index(index));
    }

    @Override
    public void switchAlias(String alias, String previousIndexPattern, String newIndex) throws IOException {
        // 删除旧绑定并添加新绑定放在一次 updateAliases 请求中，由 Elasticsearch 原子切换读写入口。
        UpdateAliasesRequest request = UpdateAliasesRequest.of(update -> update
                .actions(action -> action.remove(remove -> remove
                        .index(previousIndexPattern)
                        .alias(alias)
                        .mustExist(false)))
                .actions(action -> action.add(add -> add
                        .index(newIndex)
                        .alias(alias)
                        .isWriteIndex(true))));
        client.indices().updateAliases(request);
    }

    @Override
    public List<String> aliasTargets(String alias) throws IOException {
        if (!aliasExists(alias)) return List.of();
        return List.copyOf(client.indices().getAlias(request -> request.name(alias)).aliases().keySet());
    }

    @Override
    public boolean deleteUnaliasedIndex(String index) throws IOException {
        if (!client.indices().exists(request -> request.index(index)).value()) return true;
        var aliases = client.indices().getAlias(request -> request.index(index)).aliases();
        if (aliases.values().stream().anyMatch(value -> !value.aliases().isEmpty())) return false;
        if (!client.indices().delete(request -> request.index(index)).acknowledged()) {
            throw new IOException("Retired index deletion was not acknowledged: " + index);
        }
        return true;
    }
}
