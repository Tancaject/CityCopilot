package com.citycopilot.search;

import co.elastic.clients.elasticsearch.core.SearchRequest;

import java.io.IOException;
import java.util.List;

/**
 * Search 模块内部对 Elasticsearch 操作的最小抽象。
 *
 * <p>网关负责业务语义，该接口只隔离官方客户端 DSL，便于测试索引重建与别名切换。</p>
 */
interface ShopSearchClient {

    /**
     * @param alias 稳定索引别名
     * @return 别名是否存在
     */
    boolean aliasExists(String alias) throws IOException;

    /**
     * @param request 官方客户端查询对象
     * @return 命中文档与总数
     */
    SearchPage search(SearchRequest request) throws IOException;

    /**
     * @param alias 写入别名
     * @param document 待写入文档
     */
    void index(String alias, ElasticsearchShopDocument document) throws IOException;

    /** @param index 新一代物理索引名 */
    void createIndex(String index) throws IOException;

    /**
     * @param index 目标物理索引
     * @param documents 同一批次文档
     */
    void bulkIndex(String index, List<ElasticsearchShopDocument> documents) throws IOException;

    /** @param index 发布前需要刷新的物理索引 */
    void refresh(String index) throws IOException;

    /**
     * @param alias 对外稳定别名
     * @param previousIndexPattern 需要解绑的旧索引模式
     * @param newIndex 需要绑定的新物理索引
     */
    void switchAlias(String alias, String previousIndexPattern, String newIndex) throws IOException;

    List<String> aliasTargets(String alias) throws IOException;

    /** 已不存在也视为完成；任何 alias 仍引用目标时保留。 */
    boolean deleteUnaliasedIndex(String index) throws IOException;

    /**
     * @param hits 当前页命中
     * @param total 总命中数
     */
    record SearchPage(List<SearchEntry> hits, long total) {
        /** 对命中集合做防御性复制，避免网关读取期间被修改。 */
        public SearchPage {
            hits = List.copyOf(hits);
        }
    }

    /**
     * @param document Elasticsearch 文档
     * @param relevanceScore 相关度分数
     */
    record SearchEntry(ElasticsearchShopDocument document, double relevanceScore) {
    }
}
