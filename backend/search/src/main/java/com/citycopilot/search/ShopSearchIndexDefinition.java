package com.citycopilot.search;

import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;

import java.io.StringReader;

/** 集中保存店铺索引的 analyzer、字段 mapping 与索引创建逻辑。 */
final class ShopSearchIndexDefinition {

    /**
     * 新索引使用的完整 JSON 定义；严格 mapping 可尽早暴露投影字段漂移。
     */
    static final String INDEX_BODY = """
            {
              "settings": {
                "analysis": {
                  "tokenizer": {
                    "citycopilot_ngram_tokenizer": {
                      "type": "ngram",
                      "min_gram": 1,
                      "max_gram": 2,
                      "token_chars": ["letter", "digit"]
                    }
                  },
                  "analyzer": {
                    "citycopilot_text_index": {
                      "type": "custom",
                      "tokenizer": "citycopilot_ngram_tokenizer",
                      "filter": ["lowercase"]
                    }
                  }
                }
              },
              "mappings": {
                "dynamic": "strict",
                "properties": {
                  "id": {"type": "long"},
                  "name": {
                    "type": "text",
                    "analyzer": "citycopilot_text_index",
                    "search_analyzer": "standard",
                    "fields": {
                      "raw": {"type": "keyword"},
                      "standard": {"type": "text", "analyzer": "standard"}
                    }
                  },
                  "typeId": {"type": "long"},
                  "typeName": {
                    "type": "text",
                    "analyzer": "citycopilot_text_index",
                    "search_analyzer": "standard",
                    "fields": {
                      "raw": {"type": "keyword"},
                      "standard": {"type": "text", "analyzer": "standard"}
                    }
                  },
                  "area": {
                    "type": "text",
                    "analyzer": "citycopilot_text_index",
                    "search_analyzer": "standard",
                    "fields": {"standard": {"type": "text", "analyzer": "standard"}}
                  },
                  "address": {
                    "type": "text",
                    "analyzer": "citycopilot_text_index",
                    "search_analyzer": "standard",
                    "fields": {"standard": {"type": "text", "analyzer": "standard"}}
                  },
                  "longitude": {"type": "double"},
                  "latitude": {"type": "double"},
                  "location": {"type": "geo_point"},
                  "avgPrice": {"type": "long"},
                  "score": {"type": "integer"},
                  "sold": {"type": "integer"},
                  "openHours": {"type": "keyword"},
                  "geoStatus": {"type": "keyword"},
                  "updateTime": {"type": "date", "format": "strict_date_optional_time||epoch_millis"},
                  "projectionVersion": {"type": "long"}
                }
              }
            }
            """;

    private ShopSearchIndexDefinition() {
    }

    /**
     * @param index 待创建的物理索引名
     * @return 包含统一 mapping 的官方客户端建索引请求
     */
    static CreateIndexRequest create(String index) {
        return new CreateIndexRequest.Builder()
                .index(index)
                .withJson(new StringReader(INDEX_BODY))
                .build();
    }
}
