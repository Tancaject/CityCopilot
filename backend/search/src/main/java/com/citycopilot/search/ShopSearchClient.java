package com.citycopilot.search;

import co.elastic.clients.elasticsearch.core.SearchRequest;

import java.io.IOException;
import java.util.List;

interface ShopSearchClient {

    boolean aliasExists(String alias) throws IOException;

    SearchPage search(SearchRequest request) throws IOException;

    void index(String alias, ElasticsearchShopDocument document) throws IOException;

    void createIndex(String index) throws IOException;

    void bulkIndex(String index, List<ElasticsearchShopDocument> documents) throws IOException;

    void refresh(String index) throws IOException;

    void switchAlias(String alias, String previousIndexPattern, String newIndex) throws IOException;

    record SearchPage(List<SearchEntry> hits, long total) {
        public SearchPage {
            hits = List.copyOf(hits);
        }
    }

    record SearchEntry(ElasticsearchShopDocument document, double relevanceScore) {
    }
}
