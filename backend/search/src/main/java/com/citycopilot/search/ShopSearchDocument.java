package com.citycopilot.search;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A denormalized shop document stored in the search projection.
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

        public ShopSearchDocument build() {
            return new ShopSearchDocument(id, name, typeId, typeName, area, address, longitude, latitude,
                    avgPrice, score, sold, openHours, geoStatus, updateTime, projectionVersion);
        }
    }
}
