package com.citycopilot.core.service.discovery;

import cn.hutool.core.util.StrUtil;

final class DiscoveryGeoUtils {

    private static final double EARTH_RADIUS_METERS = 6371008.8D;

    private DiscoveryGeoUtils() {
    }

    static boolean isValidCoordinate(Double x, Double y) {
        return x != null && y != null && x >= -180D && x <= 180D && y >= -90D && y <= 90D;
    }

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

    static String normalize(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", "").toLowerCase();
    }

    static String coordinateBucket(Double value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(Math.round(value * 10000D));
    }
}
