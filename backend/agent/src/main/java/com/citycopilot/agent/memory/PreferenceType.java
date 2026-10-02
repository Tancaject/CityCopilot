package com.citycopilot.agent.memory;

/**
 * 可持久化的长期推荐偏好类型。
 *
 * <p>每项同时声明模型提示字段名，以及一个用户能否保留多个不同值。</p>
 */
public enum PreferenceType {
    /** 可多选的餐饮分类。 */
    PREFERRED_CATEGORY("preferredCategories", true),
    /** 可多选的环境或使用场景。 */
    PREFERRED_AMBIENCE("preferredAmbience", true),
    /** 单值人均价格上限。 */
    MAX_AVG_PRICE("maxAvgPrice", false),
    /** 单值最低评分。 */
    MIN_SCORE("minScore", false),
    /** 单值距离上限，单位为米。 */
    MAX_DISTANCE_METERS("maxDistanceMeters", false),
    /** 是否优先推荐可用优惠券。 */
    PREFER_VOUCHERS("preferVouchers", false);

    /** 写入模型推荐提示的字段名。 */
    private final String promptField;
    /** 同一用户是否允许保留多个值。 */
    private final boolean multiple;

    PreferenceType(String promptField, boolean multiple) {
        this.promptField = promptField;
        this.multiple = multiple;
    }

    public String promptField() {
        return promptField;
    }

    public boolean multiple() {
        return multiple;
    }
}
