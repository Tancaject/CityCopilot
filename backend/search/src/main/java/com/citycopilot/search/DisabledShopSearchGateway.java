package com.citycopilot.search;

import java.util.Collection;

/**
 * 搜索功能关闭时的空实现。
 *
 * <p>搜索只是 MySQL 店铺数据的可选投影，因此禁用态允许投影写操作成为无操作，
 * 但查询会返回明确的 {@link ShopSearchResult#SEARCH_DISABLED} 状态。</p>
 */
final class DisabledShopSearchGateway implements ShopSearchGateway {

    @Override
    public ShopSearchResult search(ShopSearchRequest request) {
        return ShopSearchResult.unavailable(ShopSearchResult.SEARCH_DISABLED);
    }

    @Override
    public void upsert(ShopSearchDocument document) {
        // 禁用模式不维护可选投影；MySQL 权威数据仍由 Core 正常写入。
    }

    @Override
    public void rebuild(Collection<ShopSearchDocument> documents) {
        // 全量重建同样跳过，避免“关闭搜索”反而触发外部依赖。
    }

    @Override
    public void rebuild(Collection<ShopSearchDocument> documents, RebuildCutover cutover) {
        // 调用方提供的水位切换逻辑只服务于搜索投影，禁用时无需执行。
    }

    @Override
    public boolean isReady() {
        return false;
    }
}
