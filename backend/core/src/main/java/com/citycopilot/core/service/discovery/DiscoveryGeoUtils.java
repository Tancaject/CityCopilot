package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;

/**
 * 店铺发现流程的地理与文本归一化工具。
 *
 * <p>仅提供无状态计算，供本地店铺与高德 POI 合并、排序和去重使用。</p>
 */
final class DiscoveryGeoUtils {

    /** Haversine 距离计算采用的平均地球半径，单位为米。 */
    private static final double EARTH_RADIUS_METERS = 6371008.8D;

    private DiscoveryGeoUtils() {
    }

    /**
     * 使用 Haversine 公式计算两组经纬度之间的球面距离。
     *
     * @param fromX 起点经度
     * @param fromY 起点纬度
     * @param toX 终点经度
     * @param toY 终点纬度
     * @return 两点距离，单位为米
     */
    static double distanceMeters(double fromX, double fromY, double toX, double toY) {
        double lat1 = Math.toRadians(fromY);
        double lat2 = Math.toRadians(toY);
        double dLat = Math.toRadians(toY - fromY);
        double dLon = Math.toRadians(toX - fromX);
        double a = Math.sin(dLat / 2D) * Math.sin(dLat / 2D)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2D) * Math.sin(dLon / 2D);
        return EARTH_RADIUS_METERS * 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
    }

    /**
     * 去除空白并转为小写，生成名称、地址去重键。
     *
     * @param value 原始文本
     * @return 可比较的归一化文本
     */
    static String normalize(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 将坐标量化到约四位小数，生成容忍轻微浮点差异的去重桶。
     *
     * @param value 经度或纬度
     * @return 量化后的字符串；空值返回空串
     */
    static String coordinateBucket(Double value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(Math.round(value * 10000D));
    }
}
