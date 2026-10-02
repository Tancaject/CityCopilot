package com.citycopilot.search;

import java.time.LocalDateTime;

/**
 * 与 Elasticsearch mapping 一一对应的内部序列化模型。
 *
 * <p>它把 API 层的 {@link ShopSearchDocument} 转换为 Elasticsearch 可直接识别的日期字符串和
 * {@code geo_point}，不向 Search 模块外暴露。</p>
 */
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

    /**
     * Elasticsearch {@code geo_point} 的经纬度结构。
     *
     * @param lat 纬度
     * @param lon 经度
     */
    record GeoPoint(Double lat, Double lon) {
    }

    /**
     * 将模块公共文档转换为索引存储模型。
     *
     * @param document 已通过合同校验的公共搜索文档
     * @return 可由官方客户端序列化的 Elasticsearch 文档
     */
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

    /** @return 转回供 Core 消费的公共搜索文档 */
    ShopSearchDocument toApiDocument() {
        return new ShopSearchDocument(id, name, typeId, typeName, area, address, longitude, latitude, avgPrice,
                score, sold, openHours, geoStatus, updateTime == null ? null : LocalDateTime.parse(updateTime),
                projectionVersion);
    }
}
