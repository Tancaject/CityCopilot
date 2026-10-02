package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 {@code citycopilot.search}，控制 Core 是否读取 Elasticsearch 投影及其恢复探测。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.search")
public class ShopSearchProperties {
    /** 是否启用搜索投影能力。 */
    private Boolean enabled = false;
    /** 搜索读取模式。 */
    private Mode mode = Mode.MYSQL;
    /** Core 期望访问的稳定 Elasticsearch 别名。 */
    private String indexAlias = "citycopilot-shop-search";
    /** 搜索投影全量对账间隔，单位毫秒。 */
    private Long reconcileIntervalMs = 60_000L;
    /** 搜索不可用后的共享就绪探测间隔，单位毫秒。 */
    private Long recoveryProbeIntervalMs = 5_000L;

    /** @return 已开启且模式不是 MYSQL 时为 {@code true} */
    public boolean isSearchReadEnabled() {
        return Boolean.TRUE.equals(enabled) && resolveMode() != Mode.MYSQL;
    }

    /** @return 当前是否需要运行搜索投影对账 */
    public boolean isReconciliationEnabled() {
        return isSearchReadEnabled();
    }

    /** @return 配置模式；空值回退到 MYSQL */
    public Mode resolveMode() {
        return mode == null ? Mode.MYSQL : mode;
    }

    /** Core 的搜索读取策略。 */
    public enum Mode {
        /** 只读 MySQL，不使用搜索投影。 */
        MYSQL,
        /** MySQL 返回业务结果，后台影子比较 Elasticsearch。 */
        SHADOW,
        /** Elasticsearch 作为搜索主读取路径。 */
        ELASTICSEARCH
    }
}
