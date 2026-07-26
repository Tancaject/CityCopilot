package com.citycopilot.core.tool;

import java.util.Locale;
import java.util.Set;

public final class BusinessErrorPolicy {
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "RATE_LIMITED",
            "TEMPORARY_UNAVAILABLE",
            "PROVIDER_UNAVAILABLE",
            "LOCK_BUSY",
            "OPERATION_IN_PROGRESS"
    );

    private BusinessErrorPolicy() {
    }

    public static String stableCode(String code) {
        if (code == null || code.isBlank()) {
            return "BUSINESS_ERROR";
        }
        return code.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    public static boolean retryable(String code) {
        String stable = stableCode(code);
        return RETRYABLE_CODES.contains(stable)
                || stable.endsWith("_TEMPORARY")
                || stable.endsWith("_TIMEOUT");
    }
}
