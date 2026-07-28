package com.citycopilot.agent.memory;

public enum PreferenceType {
    PREFERRED_CATEGORY("preferredCategories", true),
    PREFERRED_AMBIENCE("preferredAmbience", true),
    MAX_AVG_PRICE("maxAvgPrice", false),
    MIN_SCORE("minScore", false),
    MAX_DISTANCE_METERS("maxDistanceMeters", false),
    PREFER_VOUCHERS("preferVouchers", false);

    private final String promptField;
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
