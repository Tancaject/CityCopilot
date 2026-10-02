package com.citycopilot.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 绑定 {@code citycopilot.search}，集中管理搜索开关、索引命名和批处理上限。 */
@ConfigurationProperties("citycopilot.search")
public class CityCopilotSearchProperties {

    /** 是否启用 Elasticsearch 搜索投影。 */
    private boolean enabled;
    /** 线上读写使用的稳定索引别名。 */
    private String indexAlias = "citycopilot-shop-search";
    /** 每次全量重建生成物理索引名时使用的前缀。 */
    private String indexPrefix = "citycopilot-shop";
    /** 单次查询允许返回的最大文档数。 */
    private int maxPageSize = 500;
    /** 全量重建时每个 bulk 请求包含的最大文档数。 */
    private int bulkBatchSize = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIndexAlias() {
        return indexAlias;
    }

    public void setIndexAlias(String indexAlias) {
        this.indexAlias = requireText(indexAlias, "indexAlias");
    }

    public String getIndexPrefix() {
        return indexPrefix;
    }

    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = requireText(indexPrefix, "indexPrefix");
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        if (maxPageSize <= 0) {
            throw new IllegalArgumentException("maxPageSize must be positive");
        }
        this.maxPageSize = maxPageSize;
    }

    public int getBulkBatchSize() {
        return bulkBatchSize;
    }

    public void setBulkBatchSize(int bulkBatchSize) {
        if (bulkBatchSize <= 0) {
            throw new IllegalArgumentException("bulkBatchSize must be positive");
        }
        this.bulkBatchSize = bulkBatchSize;
    }

    /**
     * 校验并规范化必须为非空文本的配置项。
     *
     * @param value 配置值
     * @param field 用于错误信息的字段名
     * @return 去除首尾空白后的配置值
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
