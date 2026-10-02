package com.citycopilot.search;

import java.util.List;
import java.util.Objects;

/**
 * 带显式可用状态的店铺搜索结果；“可用但无命中”与“搜索不可用”是两种不同结果。
 *
 * @param availability 搜索投影当前是否可用
 * @param hits 当前页命中数据
 * @param total 满足条件的总文档数
 * @param unavailableReason 不可用时的稳定原因码
 */
public record ShopSearchResult(
        Availability availability,
        List<ShopSearchHit> hits,
        long total,
        String unavailableReason) {

    /** 配置明确关闭搜索。 */
    public static final String SEARCH_DISABLED = "SEARCH_DISABLED";
    /** 稳定别名尚未指向可查询索引。 */
    public static final String INDEX_NOT_READY = "INDEX_NOT_READY";
    /** 本节点最近一次投影写入失败，可能读取旧数据。 */
    public static final String INDEX_STALE = "INDEX_STALE";
    /** Elasticsearch 请求失败或客户端不可用。 */
    public static final String SEARCH_UNAVAILABLE = "SEARCH_UNAVAILABLE";

    /** 搜索投影的可用状态。 */
    public enum Availability {
        /** 可以信任命中结果，包括空结果。 */
        AVAILABLE,
        /** 不应使用命中数据，应由上层选择降级路径。 */
        UNAVAILABLE
    }

    /** 校验可用状态、结果数据和不可用原因三者保持一致。 */
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

    /** @return 当前结果是否来自可用搜索投影 */
    public boolean available() {
        return availability == Availability.AVAILABLE;
    }

    /**
     * @param hits 当前页命中数据
     * @param total 总命中数
     * @return 可用搜索结果
     */
    public static ShopSearchResult available(List<ShopSearchHit> hits, long total) {
        return new ShopSearchResult(Availability.AVAILABLE, hits, total, null);
    }

    /** @return 可用但没有命中的搜索结果 */
    public static ShopSearchResult empty() {
        return available(List.of(), 0);
    }

    /**
     * @param reason 稳定不可用原因码
     * @return 不携带命中数据的不可用结果
     */
    public static ShopSearchResult unavailable(String reason) {
        return new ShopSearchResult(Availability.UNAVAILABLE, List.of(), 0, reason);
    }
}
