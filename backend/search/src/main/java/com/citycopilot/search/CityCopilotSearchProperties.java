package com.citycopilot.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citycopilot.search")
public class CityCopilotSearchProperties {

    private boolean enabled;
    private String indexAlias = "citycopilot-shop-search";
    private String indexPrefix = "citycopilot-shop";
    private int maxPageSize = 500;
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
