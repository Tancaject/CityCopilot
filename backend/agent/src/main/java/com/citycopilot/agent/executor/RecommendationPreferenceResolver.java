package com.citycopilot.agent.executor;

import com.citycopilot.agent.memory.PreferenceVocabulary;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐类工具参数的偏好合并器。
 *
 * <p>优先采用当前轮明确表达，其次保留模型已给出的有效分类，最后才从长期偏好提示补全；当前轮明确排除项始终生效。</p>
 */
final class RecommendationPreferenceResolver {
    /** 会话格式中标记当前用户消息起点的文本。 */
    private static final String CURRENT_USER_MARKER = "Current user:";
    /** 附近店铺工具接收的规范分类参数名。 */
    private static final String PREFERRED_CATEGORY_ARG = "preferredCategory";

    private RecommendationPreferenceResolver() {
    }

    /**
     * 为支持偏好的工具补全参数，同时不修改模型传入的原始 Map。
     *
     * @return 合并后的可变参数副本
     */
    static Map<String, Object> applyToolDefaults(
            String toolName,
            String message,
            Map<String, Object> hints,
            Map<String, Object> originalArgs
    ) {
        Map<String, Object> resolved = originalArgs == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(originalArgs);
        if ("search_nearby_shops".equals(toolName)) {
            applyNearbyCategory(message, hints, resolved);
        }
        return resolved;
    }

    /** 按“当前请求、已有参数、记忆偏好”的优先级解析附近店铺分类。 */
    static void applyNearbyCategory(String message, Map<String, Object> hints, Map<String, Object> args) {
        if (args == null) {
            return;
        }
        PreferenceVocabulary.CategorySelection current = PreferenceVocabulary.categoriesIn(currentRequest(message));
        if (!current.included().isEmpty()) {
            args.put(PREFERRED_CATEGORY_ARG, current.included().get(0));
            return;
        }
        Object existingCategory = args.get(PREFERRED_CATEGORY_ARG);
        if (existingCategory instanceof String existing && !existing.isBlank()) {
            String canonical = PreferenceVocabulary.canonicalCategory(existing).orElse(null);
            if (canonical == null || !current.excluded().contains(canonical)) {
                return;
            }
            args.remove(PREFERRED_CATEGORY_ARG);
        }
        for (String remembered : rememberedCategories(hints)) {
            String canonical = PreferenceVocabulary.canonicalCategory(remembered).orElse(null);
            if (canonical != null && !current.excluded().contains(canonical)) {
                args.put(PREFERRED_CATEGORY_ARG, canonical);
                return;
            }
        }
    }

    private static List<String> rememberedCategories(Map<String, Object> hints) {
        Object preferencesValue = hints == null ? null : hints.get("userPreferences");
        if (!(preferencesValue instanceof Map<?, ?> preferences)) {
            return List.of();
        }
        Object valuesValue = preferences.get("values");
        if (!(valuesValue instanceof Map<?, ?> values)) {
            return List.of();
        }
        Object categoriesValue = values.get("preferredCategories");
        if (categoriesValue instanceof String category) {
            return List.of(category);
        }
        if (!(categoriesValue instanceof Collection<?> categories)) {
            return List.of();
        }
        return categories.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    /** @return 从格式化会话中截取最后一段当前用户请求 */
    static String currentRequest(String message) {
        if (message == null) {
            return "";
        }
        int marker = message.lastIndexOf(CURRENT_USER_MARKER);
        return marker < 0 ? message : message.substring(marker + CURRENT_USER_MARKER.length());
    }
}
