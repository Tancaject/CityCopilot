package com.hmdp.config;

import cn.hutool.core.util.StrUtil;
import com.hmdp.utils.SystemConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.geo")
public class GeoProperties {
    private Boolean autoSyncShopIndex = true;
    private Double nearbyRadiusMeters = 5000D;
    private Integer maxBatchSize = 100;
    private String defaultCity = "杭州";
    private Boolean cityLimit = true;
    private Integer shopPageSize = SystemConstants.DEFAULT_PAGE_SIZE;
    private Integer maxShopPageSize = SystemConstants.MAX_PAGE_SIZE;

    public String resolveCity(String city) {
        return StrUtil.blankToDefault(city, StrUtil.blankToDefault(defaultCity, "杭州"));
    }

    public int resolveShopPageSize(Integer requestedSize) {
        int defaultSize = positiveOrDefault(shopPageSize, SystemConstants.DEFAULT_PAGE_SIZE);
        int maxSize = positiveOrDefault(maxShopPageSize, Math.max(defaultSize, SystemConstants.MAX_PAGE_SIZE));
        int targetSize = requestedSize == null ? defaultSize : requestedSize;
        return Math.max(1, Math.min(targetSize, maxSize));
    }

    public boolean isCityLimitEnabled() {
        return cityLimit == null || cityLimit;
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
