package com.citycopilot.contract.commerce;

import java.util.Set;

/**
 * 统一解释 Commerce 秒杀准入失败是否允许调用方重试。
 *
 * <p>该策略放在 contracts 模块中，确保 Core 客户端与 Commerce 服务端对错误码的判断一致。</p>
 */
public final class CommerceAdmissionOutcomePolicy {
    /** 准入结果无法确认，重试前仍需沿用同一幂等请求。 */
    public static final String COMMERCE_ADMISSION_OUTCOME_UNKNOWN =
            "COMMERCE_ADMISSION_OUTCOME_UNKNOWN";
    /** 准入结果未能可靠持久化，调用方可稍后重试。 */
    public static final String COMMERCE_DURABLE_ADMISSION_UNAVAILABLE =
            "COMMERCE_DURABLE_ADMISSION_UNAVAILABLE";

    /** 允许重试的稳定错误码集合。 */
    private static final Set<String> RETRYABLE_CODES = Set.of(
            COMMERCE_ADMISSION_OUTCOME_UNKNOWN,
            COMMERCE_DURABLE_ADMISSION_UNAVAILABLE
    );

    private CommerceAdmissionOutcomePolicy() {
    }

    /**
     * 判断指定 Commerce 错误码是否属于可重试失败。
     *
     * @param code Commerce 返回的机器可读错误码，可为 {@code null}
     * @return 错误码允许重试时返回 {@code true}
     */
    public static boolean isRetryable(String code) {
        return RETRYABLE_CODES.contains(code);
    }
}
