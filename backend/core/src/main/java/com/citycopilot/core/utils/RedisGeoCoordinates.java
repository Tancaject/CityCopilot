package com.citycopilot.core.utils;

/** 校验坐标是否位于 Redis GEO 支持的经纬度范围内。 */
public final class RedisGeoCoordinates {
    /** Redis GEO 支持的绝对最大经度。 */
    private static final double MAX_LONGITUDE = 180D;
    /** Web Mercator/Redis GEO 支持的绝对最大纬度。 */
    private static final double MAX_LATITUDE = 85.05112878D;

    private RedisGeoCoordinates() {
    }

    /**
     * @param longitude 经度
     * @param latitude 纬度
     * @return 两者非空、有限且均处于 Redis GEO 可写范围时为 {@code true}
     */
    public static boolean isValid(Double longitude, Double latitude) {
        return longitude != null
                && latitude != null
                && Double.isFinite(longitude)
                && Double.isFinite(latitude)
                && longitude >= -MAX_LONGITUDE
                && longitude <= MAX_LONGITUDE
                && latitude >= -MAX_LATITUDE
                && latitude <= MAX_LATITUDE;
    }
}
