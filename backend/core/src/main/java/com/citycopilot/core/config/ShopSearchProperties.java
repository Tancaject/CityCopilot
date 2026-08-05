package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.search")
public class ShopSearchProperties {
    private Boolean enabled = false;
    private Mode mode = Mode.MYSQL;
    private String indexAlias = "citycopilot-shop-search";
    private Long reconcileIntervalMs = 60_000L;
    private Long recoveryProbeIntervalMs = 5_000L;

    public boolean isSearchReadEnabled() {
        return Boolean.TRUE.equals(enabled) && resolveMode() != Mode.MYSQL;
    }

    public boolean isReconciliationEnabled() {
        return isSearchReadEnabled();
    }

    public Mode resolveMode() {
        return mode == null ? Mode.MYSQL : mode;
    }

    public enum Mode {
        MYSQL,
        SHADOW,
        ELASTICSEARCH
    }
}
