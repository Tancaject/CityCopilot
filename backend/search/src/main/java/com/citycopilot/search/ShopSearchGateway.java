package com.citycopilot.search;

import java.util.Collection;
import java.util.List;

/**
 * Core 店铺发现功能访问可选搜索投影的模块边界。
 *
 * <p>MySQL 始终是权威数据源；实现类只负责查询和维护可重建的 Elasticsearch 投影。</p>
 */
public interface ShopSearchGateway {

    /**
     * @param request 已校验的搜索条件
     * @return 带显式可用状态的搜索结果，不以空列表掩盖基础设施失败
     */
    ShopSearchResult search(ShopSearchRequest request);

    /**
     * 按投影版本新增或更新单个店铺文档。
     *
     * @param document 最新店铺投影
     */
    void upsert(ShopSearchDocument document);

    /**
     * 从权威快照重建整个搜索索引，并在完成后切换稳定别名。
     *
     * @param documents 水位点对应的完整店铺快照
     */
    void rebuild(Collection<ShopSearchDocument> documents);

    /**
     * 在隔离索引中开始构建，由持有源数据水位屏障的调用方分页写入、追平增量并发布。
     *
     * @param documents 初始文档；分页构建时可为空，由回调逐页补入
     * @param cutover 负责完成基础页、水位后变更并决定何时发布新索引的回调
     */
    void rebuild(Collection<ShopSearchDocument> documents, RebuildCutover cutover);

    /** 调用方须持有 writer fence；只删除精确命名、未绑定任何 alias 的已登记退役代际。 */
    default CleanupDecision deleteRetiredGeneration(String generation) {
        throw new UnsupportedOperationException("This search gateway does not support generation cleanup");
    }

    enum CleanupDecision { REMOVED_OR_ABSENT, RETAINED_ALIAS, RETAINED_UNREADY }

    /** 控制新索引从“待发布”到“线上别名”的切换过程。 */
    @FunctionalInterface
    interface RebuildCutover {
        /**
         * @param generation 已写入 documents 参数、尚未发布的新索引
         */
        void execute(PendingGeneration generation);
    }

    /** 只允许先追平增量、再发布一次的待发布索引句柄。 */
    interface PendingGeneration {
        /** @param documents 有界基础页或水位点之后产生的增量店铺投影 */
        void upsertAll(Collection<ShopSearchDocument> documents);

        /** 刷新新索引并原子切换稳定别名。 */
        void publish();

        /** 成功发布时被解除稳定 alias 的旧物理索引；构建中代际不会出现在这里。 */
        default List<String> retiredGenerations() {
            return List.of();
        }
    }

    /** @return 共享别名可访问时为 {@code true}；实现可同时清除节点本地的暂态失败标记 */
    default boolean probeSharedReadiness() {
        return isReady();
    }

    /** @return 本节点当前是否认为搜索投影可供业务读取 */
    boolean isReady();
}
