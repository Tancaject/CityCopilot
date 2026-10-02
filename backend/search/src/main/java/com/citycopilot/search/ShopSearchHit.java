package com.citycopilot.search;

import java.util.Objects;

/**
 * 单条店铺搜索命中。
 *
 * @param document 返回给 Core 的店铺投影文档
 * @param relevanceScore Elasticsearch 计算的相关度分数
 */
public record ShopSearchHit(ShopSearchDocument document, double relevanceScore) {

    /** 保证命中结果一定包含可展示的店铺文档。 */
    public ShopSearchHit {
        Objects.requireNonNull(document, "document must not be null");
    }
}
