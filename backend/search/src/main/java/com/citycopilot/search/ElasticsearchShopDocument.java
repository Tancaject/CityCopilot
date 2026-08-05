package com.citycopilot.search;

import java.time.LocalDateTime;

record ElasticsearchShopDocument(
        Long id,
        String name,
        Long typeId,
        String typeName,
        String area,
        String address,
        Double longitude,
        Double latitude,
        Long avgPrice,
        Integer score,
        Integer sold,
        String openHours,
        String geoStatus,
        String updateTime,
        Long projectionVersion,
        GeoPoint location) {

    record GeoPoint(Double lat, Double lon) {
    }

    static ElasticsearchShopDocument from(ShopSearchDocument document) {
        GeoPoint point = document.latitude() == null
                ? null
                : new GeoPoint(document.latitude(), document.longitude());
        return new ElasticsearchShopDocument(
                document.id(), document.name(), document.typeId(), document.typeName(), document.area(),
                document.address(), document.longitude(), document.latitude(), document.avgPrice(), document.score(),
                document.sold(), document.openHours(), document.geoStatus(),
                document.updateTime() == null ? null : document.updateTime().toString(),
                document.projectionVersion(), point);
    }

    ShopSearchDocument toApiDocument() {
        return new ShopSearchDocument(id, name, typeId, typeName, area, address, longitude, latitude, avgPrice,
                score, sold, openHours, geoStatus, updateTime == null ? null : LocalDateTime.parse(updateTime),
                projectionVersion);
    }
}
