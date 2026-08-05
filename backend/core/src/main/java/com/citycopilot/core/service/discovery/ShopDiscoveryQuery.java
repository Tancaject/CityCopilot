package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.utils.SystemConstants;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ShopDiscoveryQuery {
    private Integer typeId;
    private String keyword;
    private String preferredCategory;
    private Integer current;
    private Integer size;
    private Double x;
    private Double y;

    public boolean hasPoint() {
        return x != null || y != null;
    }

    public boolean hasValidPoint() {
        return DiscoveryGeoUtils.isValidCoordinate(x, y);
    }

    public boolean hasKeyword() {
        return StrUtil.isNotBlank(keyword);
    }

    public int resolveCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public int resolvePageSize(GeoProperties geoProperties) {
        if (geoProperties == null) {
            return size == null || size < 1 ? SystemConstants.DEFAULT_PAGE_SIZE : size;
        }
        return geoProperties.resolveShopPageSize(size);
    }

    public int resolveFetchLimit(GeoProperties geoProperties) {
        long limit = (long) resolveCurrent() * resolvePageSize(geoProperties);
        return (int) Math.min(limit, Integer.MAX_VALUE);
    }

    public int resolveCandidateLimit(GeoProperties geoProperties) {
        int maximum = geoProperties == null ? 500 : geoProperties.resolveRedisMaxCandidates();
        return Math.min(resolveFetchLimit(geoProperties), maximum);
    }

    public long resolveOffset(GeoProperties geoProperties) {
        return (long) (resolveCurrent() - 1) * resolvePageSize(geoProperties);
    }

    public String normalizedKeyword() {
        return StrUtil.blankToDefault(keyword, "").trim();
    }

    public boolean hasPreferredCategory() {
        return StrUtil.isNotBlank(preferredCategory);
    }

    public String normalizedPreferredCategory() {
        return StrUtil.blankToDefault(preferredCategory, "").trim();
    }
}
