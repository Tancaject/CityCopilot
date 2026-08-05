package com.citycopilot.search;

/**
 * Search criteria. Category preference affects ranking only; it is never a hard filter.
 */
public record ShopSearchRequest(
        String keyword,
        String preferredCategory,
        Long typeId,
        Long minAvgPrice,
        Long maxAvgPrice,
        Integer minScore,
        Double longitude,
        Double latitude,
        Double radiusMeters,
        int offset,
        int size) {

    public static final int DEFAULT_SIZE = 20;

    public ShopSearchRequest {
        keyword = trimToNull(keyword);
        preferredCategory = trimToNull(preferredCategory);
        if (minAvgPrice != null && minAvgPrice < 0) {
            throw new IllegalArgumentException("minAvgPrice must not be negative");
        }
        if (maxAvgPrice != null && maxAvgPrice < 0) {
            throw new IllegalArgumentException("maxAvgPrice must not be negative");
        }
        if (minAvgPrice != null && maxAvgPrice != null && minAvgPrice > maxAvgPrice) {
            throw new IllegalArgumentException("minAvgPrice must not exceed maxAvgPrice");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        boolean anyGeoValue = longitude != null || latitude != null || radiusMeters != null;
        boolean allGeoValues = longitude != null && latitude != null && radiusMeters != null;
        if (anyGeoValue && !allGeoValues) {
            throw new IllegalArgumentException("longitude, latitude and radiusMeters must be provided together");
        }
        if (longitude != null && (longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        if (latitude != null && (latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (radiusMeters != null && radiusMeters <= 0) {
            throw new IllegalArgumentException("radiusMeters must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Builder {
        private String keyword;
        private String preferredCategory;
        private Long typeId;
        private Long minAvgPrice;
        private Long maxAvgPrice;
        private Integer minScore;
        private Double longitude;
        private Double latitude;
        private Double radiusMeters;
        private int offset;
        private int size = DEFAULT_SIZE;

        private Builder() {
        }

        public Builder keyword(String value) { this.keyword = value; return this; }
        public Builder preferredCategory(String value) { this.preferredCategory = value; return this; }
        public Builder typeId(Long value) { this.typeId = value; return this; }
        public Builder minAvgPrice(Long value) { this.minAvgPrice = value; return this; }
        public Builder maxAvgPrice(Long value) { this.maxAvgPrice = value; return this; }
        public Builder minScore(Integer value) { this.minScore = value; return this; }
        public Builder longitude(Double value) { this.longitude = value; return this; }
        public Builder latitude(Double value) { this.latitude = value; return this; }
        public Builder radiusMeters(Double value) { this.radiusMeters = value; return this; }
        public Builder offset(int value) { this.offset = value; return this; }
        public Builder size(int value) { this.size = value; return this; }

        public ShopSearchRequest build() {
            return new ShopSearchRequest(keyword, preferredCategory, typeId, minAvgPrice, maxAvgPrice,
                    minScore, longitude, latitude, radiusMeters, offset, size);
        }
    }
}
