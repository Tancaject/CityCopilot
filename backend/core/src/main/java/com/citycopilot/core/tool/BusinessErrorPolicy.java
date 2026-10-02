package com.citycopilot.core.tool;

import java.util.Locale;
import java.util.Set;

/**
 * Agent 工具业务错误的稳定编码与重试策略。
 *
 * <p>把各业务服务返回的错误码统一为协议格式，避免调用方依赖不稳定的提示文本。</p>
 */
public final class BusinessErrorPolicy {

    /** 明确允许 Agent 在相同业务语义下重试的瞬时错误码。 */
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "RATE_LIMITED",
            "TEMPORARY_UNAVAILABLE",
            "PROVIDER_UNAVAILABLE",
            "LOCK_BUSY",
            "OPERATION_IN_PROGRESS"
    );

    private BusinessErrorPolicy() {
    }

    /**
     * 将业务错误码规范化为大写下划线形式。
     *
     * @param code 原始错误码
     * @return 非空稳定错误码；缺失时返回 {@code BUSINESS_ERROR}
     */
    public static String stableCode(String code) {
        if (code == null || code.isBlank()) {
            return "BUSINESS_ERROR";
        }
        return code.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 判断错误是否表示临时故障而可安全重试。
     *
     * @param code 原始或已规范化错误码
     * @return 是否可重试
     */
    public static boolean retryable(String code) {
        String stable = stableCode(code);
        return RETRYABLE_CODES.contains(stable)
                || stable.endsWith("_TEMPORARY")
                || stable.endsWith("_TIMEOUT");
    }
}
