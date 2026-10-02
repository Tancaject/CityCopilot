package com.citycopilot.core.config;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.utils.SystemConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 {@code citycopilot.geo}，统一地理发现、GEO 投影和对账任务参数。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.geo")
public class GeoProperties {
    /** Core 启动时是否自动同步店铺 GEO 索引。 */
    private Boolean autoSyncShopIndex = true;
    /** 未指定半径时的附近检索范围，单位米。 */
    private Double nearbyRadiusMeters = 5000D;
    /** 单个地理批处理任务最大店铺数。 */
    private Integer maxBatchSize = 100;
    /** 请求未指定城市时使用的城市。 */
    private String defaultCity = "杭州";
    /** 是否把外部 POI 搜索限制在当前城市。 */
    private Boolean cityLimit = true;
    /** 未指定时的店铺发现页大小。 */
    private Integer shopPageSize = SystemConstants.DEFAULT_PAGE_SIZE;
    /** 店铺发现页大小硬上限。 */
    private Integer maxShopPageSize = SystemConstants.MAX_PAGE_SIZE;
    /** 本地发现使用 MySQL、Redis 影子校验或 Redis 主读。 */
    private LocalDiscoveryMode localDiscoveryMode = LocalDiscoveryMode.REDIS;
    /** Redis GEO 初选数量相对最终页大小的倍数。 */
    private Integer redisCandidateMultiplier = 3;
    /** Redis GEO 单次允许拉取的最大候选数。 */
    private Integer redisMaxCandidates = 500;
    /** 是否启用 GEO 投影定时对账。 */
    private Boolean reconcileEnabled = true;
    /** GEO 对账任务间隔，单位毫秒。 */
    private Long reconcileIntervalMs = 60000L;

    /**
     * @param city 请求城市
     * @return 请求值、配置默认值或杭州
     */
    public String resolveCity(String city) {
        return StrUtil.blankToDefault(city, StrUtil.blankToDefault(defaultCity, "杭州"));
    }

    /**
     * @param requestedSize 请求页大小
     * @return 限制在 1 到有效最大值之间的页大小
     */
    public int resolveShopPageSize(Integer requestedSize) {
        int defaultSize = positiveOrDefault(shopPageSize, SystemConstants.DEFAULT_PAGE_SIZE);
        int maxSize = positiveOrDefault(maxShopPageSize, Math.max(defaultSize, SystemConstants.MAX_PAGE_SIZE));
        int targetSize = requestedSize == null ? defaultSize : requestedSize;
        return Math.max(1, Math.min(targetSize, maxSize));
    }

    /** @return 未显式关闭时为 {@code true} */
    public boolean isCityLimitEnabled() {
        return cityLimit == null || cityLimit;
    }

    /** @return 配置模式；空值安全回退到 REDIS */
    public LocalDiscoveryMode resolveLocalDiscoveryMode() {
        return localDiscoveryMode == null ? LocalDiscoveryMode.REDIS : localDiscoveryMode;
    }

    /** @return 正数候选倍数；无效配置回退到 3 */
    public int resolveRedisCandidateMultiplier() {
        return positiveOrDefault(redisCandidateMultiplier, 3);
    }

    /** @return 正数最大候选数；无效配置回退到 500 */
    public int resolveRedisMaxCandidates() {
        return positiveOrDefault(redisMaxCandidates, 500);
    }

    /** @return value 为正数时使用 value，否则使用 fallback */
    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    /** 本地店铺发现读取策略。 */
    public enum LocalDiscoveryMode {
        /** 直接查询 MySQL。 */
        MYSQL,
        /** MySQL 返回结果，同时影子比较 Redis GEO。 */
        SHADOW,
        /** Redis GEO 作为主读取路径。 */
        REDIS
    }
}
