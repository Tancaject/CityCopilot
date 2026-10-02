package com.citycopilot.search;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 存入搜索投影的反规范化店铺文档，避免查询时再跨表组装分类与地理信息。
 *
 * @param id 店铺主键，同时作为 Elasticsearch 文档 ID
 * @param name 店铺名称
 * @param typeId 分类主键
 * @param typeName 分类名称
 * @param area 所在区域
 * @param address 详细地址
 * @param longitude 经度
 * @param latitude 纬度
 * @param avgPrice 人均价格
 * @param score 评分
 * @param sold 销量
 * @param openHours 营业时间文本
 * @param geoStatus 地理编码状态
 * @param updateTime 权威数据更新时间
 * @param projectionVersion 外部版本号，防止旧投影覆盖新数据
 */
public record ShopSearchDocument(
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
        LocalDateTime updateTime,
        Long projectionVersion) {

    /** 规范化可选文本，并校验 ID、坐标和投影版本满足索引合同。 */
    public ShopSearchDocument {
        Objects.requireNonNull(id, "id must not be null");
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        name = requireText(name, "name");
        typeName = trimToNull(typeName);
        area = trimToNull(area);
        address = trimToNull(address);
        openHours = trimToNull(openHours);
        geoStatus = trimToNull(geoStatus);
        projectionVersion = projectionVersion == null ? 1L : Math.max(1L, projectionVersion);
        if ((longitude == null) != (latitude == null)) {
            throw new IllegalArgumentException("longitude and latitude must be provided together");
        }
        if (longitude != null && (longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        if (latitude != null && (latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
    }

    /** @return 新的文档构建器 */
    public static Builder builder() {
        return new Builder();
    }

    private static String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 收集店铺投影字段，最终由 record 构造器完成统一校验。 */
    public static final class Builder {
        private Long id;
        private String name;
        private Long typeId;
        private String typeName;
        private String area;
        private String address;
        private Double longitude;
        private Double latitude;
        private Long avgPrice;
        private Integer score;
        private Integer sold;
        private String openHours;
        private String geoStatus;
        private LocalDateTime updateTime;
        private Long projectionVersion;

        private Builder() {
        }

        public Builder id(Long value) { this.id = value; return this; }
        public Builder name(String value) { this.name = value; return this; }
        public Builder typeId(Long value) { this.typeId = value; return this; }
        public Builder typeName(String value) { this.typeName = value; return this; }
        public Builder area(String value) { this.area = value; return this; }
        public Builder address(String value) { this.address = value; return this; }
        public Builder longitude(Double value) { this.longitude = value; return this; }
        public Builder latitude(Double value) { this.latitude = value; return this; }
        public Builder avgPrice(Long value) { this.avgPrice = value; return this; }
        public Builder score(Integer value) { this.score = value; return this; }
        public Builder sold(Integer value) { this.sold = value; return this; }
        public Builder openHours(String value) { this.openHours = value; return this; }
        public Builder geoStatus(String value) { this.geoStatus = value; return this; }
        public Builder updateTime(LocalDateTime value) { this.updateTime = value; return this; }
        public Builder projectionVersion(Long value) { this.projectionVersion = value; return this; }

        /** @return 经过规范化与校验的不可变搜索文档 */
        public ShopSearchDocument build() {
            return new ShopSearchDocument(id, name, typeId, typeName, area, address, longitude, latitude,
                    avgPrice, score, sold, openHours, geoStatus, updateTime, projectionVersion);
        }
    }
}
