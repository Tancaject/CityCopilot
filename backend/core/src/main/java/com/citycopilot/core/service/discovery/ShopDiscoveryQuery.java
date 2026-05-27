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
        return resolveCurrent() * resolvePageSize(geoProperties);
    }

    public String normalizedKeyword() {
        return StrUtil.blankToDefault(keyword, "").trim();
    }
}
