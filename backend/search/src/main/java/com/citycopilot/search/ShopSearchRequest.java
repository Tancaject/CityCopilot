package com.citycopilot.search;

/**
 * 店铺搜索条件；偏好分类只影响排序，不会作为硬过滤条件排除其他店铺。
 *
 * @param keyword 匹配店名、分类、区域和地址的关键词
 * @param preferredCategory 用于相关度加权的偏好分类
 * @param typeId 必须匹配的店铺分类主键
 * @param minAvgPrice 最低人均价格
 * @param maxAvgPrice 最高人均价格
 * @param minScore 最低评分
 * @param longitude 距离检索中心经度
 * @param latitude 距离检索中心纬度
 * @param radiusMeters 检索半径，单位米
 * @param offset 分页起始偏移量
 * @param size 请求页大小
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

    /** Builder 未指定页大小时使用的默认值。 */
    public static final int DEFAULT_SIZE = 20;

    /** 规范化文本并校验价格、分页和 GEO 参数必须成组且处于有效范围。 */
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

    /** @return 新的可变构建器，最终由 record 构造器统一校验 */
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

    /** 收集可选搜索条件，避免调用点使用冗长的 record 构造参数列表。 */
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

        /** @return 完成校验并创建不可变搜索请求 */
        public ShopSearchRequest build() {
            return new ShopSearchRequest(keyword, preferredCategory, typeId, minAvgPrice, maxAvgPrice,
                    minScore, longitude, latitude, radiusMeters, offset, size);
        }
    }
}
