package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.utils.RedisGeoCoordinates;
import com.citycopilot.core.utils.SystemConstants;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 各店铺发现数据源共享的查询对象，集中处理坐标、分页和文本标准化。
 */
@Data
@Accessors(chain = true)
public class ShopDiscoveryQuery {

    /** 可选店铺分类主键。 */
    private Integer typeId;

    /** 可选名称或地址关键词。 */
    private String keyword;

    /** 从 1 开始的页码。 */
    private Integer current;

    /** 客户端请求的每页条数，最终受 GEO 配置上限约束。 */
    private Integer size;

    /** 查询中心经度。 */
    private Double x;

    /** 查询中心纬度。 */
    private Double y;

    /** Explicit discovery radius, when the caller supplied a distance constraint. */
    private Double radiusMeters;

    /** @return 请求是否提供了任一坐标分量 */
    public boolean hasPoint() {
        return x != null || y != null;
    }

    /** @return 经纬度是否同时存在并符合 Redis GEO 接受范围 */
    public boolean hasValidPoint() {
        return RedisGeoCoordinates.isValid(x, y);
    }

    /** @return 规范化后从 1 开始的页码 */
    public int resolveCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    /**
     * 按全局 GEO 配置限制每页大小。
     *
     * @param geoProperties 可选 GEO 配置
     * @return 有效每页条数
     */
    public int resolvePageSize(GeoProperties geoProperties) {
        if (geoProperties == null) {
            return size == null || size < 1 ? SystemConstants.DEFAULT_PAGE_SIZE : size;
        }
        return geoProperties.resolveShopPageSize(size);
    }

    /**
     * 计算为了完成当前页需要从数据源读取的累计条数。
     *
     * @param geoProperties GEO 分页配置
     * @return 页码乘以每页大小，并限制在整数范围内
     */
    public int resolveFetchLimit(GeoProperties geoProperties) {
        long limit = (long) resolveCurrent() * resolvePageSize(geoProperties);
        return (int) Math.min(limit, Integer.MAX_VALUE);
    }

    /**
     * 计算 Redis GEO 允许拉取的候选上限，避免深页请求无限放大。
     *
     * @param geoProperties GEO 候选限制配置
     * @return 当前请求的候选上限
     */
    public int resolveCandidateLimit(GeoProperties geoProperties) {
        int maximum = geoProperties == null ? 500 : geoProperties.resolveRedisMaxCandidates();
        return Math.min(resolveFetchLimit(geoProperties), maximum);
    }

    /**
     * @param geoProperties GEO 分页配置
     * @return 当前页在完整候选列表中的零基偏移
     */
    public long resolveOffset(GeoProperties geoProperties) {
        return (long) (resolveCurrent() - 1) * resolvePageSize(geoProperties);
    }

    /** @return 去除首尾空白后的关键词 */
    public String normalizedKeyword() {
        return StrUtil.blankToDefault(keyword, "").trim();
    }
}
