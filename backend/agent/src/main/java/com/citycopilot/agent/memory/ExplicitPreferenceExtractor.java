package com.citycopilot.agent.memory;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从中文用户消息中提取“明确且可长期保存”的推荐偏好。
 *
 * <p>只有“记住、以后、默认、我喜欢”等持久化信号才触发写入；普通查询不会污染长期记忆。输出限定在受控词表和数值范围，不把自由指令存入模型上下文。</p>
 */
@Component
public final class ExplicitPreferenceExtractor {
    /** 表明用户希望长期记忆的措辞。 */
    private static final Pattern DURABLE_CUE = Pattern.compile(
            "请?记住|帮我记住|以后|今后|默认|长期偏好|我的偏好|我的预算|我(?:平时|通常|一般)"
    );
    /** 可直接视为持久偏好的第一人称喜好表达。 */
    private static final Pattern DIRECT_LIKE_CUE = Pattern.compile("我(?:更|比较)?(?:喜欢|偏好|爱吃)");
    /** 清除全部长期偏好的明确指令。 */
    private static final Pattern CLEAR_ALL = Pattern.compile(
            "(?:忘记|清除|删除|重置).{0,8}(?:所有|全部|我的)?(?:长期)?偏好|不再记住.{0,8}偏好"
    );
    /** 人均预算上限及其数值捕获组。 */
    private static final Pattern PRICE = Pattern.compile(
            "(?:人均|每人预算|预算)\\s*(?:不超过|最多|控制在|为|是|大约|约)?\\s*(\\d{1,5})\\s*元?\\s*(?:以内|以下|左右|封顶)?"
    );
    /** 最低评分表达式。 */
    private static final Pattern SCORE = Pattern.compile(
            "评分\\s*(\\d(?:\\.\\d)?)\\s*(?:以上|起|至少)"
    );
    /** 距离上限，兼容米与公里单位。 */
    private static final Pattern DISTANCE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米|m)\\s*(?:以内|内|之内|最多)"
    );
    /** 明确偏好优惠券的表达式。 */
    private static final Pattern VOUCHER_TRUE = Pattern.compile(
            "有券优先|优惠券优先|优先.{0,6}(?:有券|优惠券)|默认.{0,6}(?:有券|优惠券)"
    );
    /** 明确取消优惠券优先的表达式。 */
    private static final Pattern VOUCHER_FALSE = Pattern.compile(
            "不用考虑优惠券|不需要优惠券|不优先.{0,4}(?:有券|优惠券)"
    );
    /** 类别或环境词前必须出现的偏好上下文。 */
    private static final Pattern PREFERENCE_PREFIX = Pattern.compile(
            ".*(?:记住[，,]?|喜欢|偏好|爱吃|默认|优先)[^，。！？,!?；;]{0,8}$"
    );
    /** 允许删除旧偏好的纠正上下文。 */
    private static final Pattern CORRECTION_PREFIX = Pattern.compile(
            ".*(?:后来|现在|改为|改成|以后)[^，。！？,!?；;]{0,4}$"
    );
    /** 同时出现这些词时，记忆更新后仍需继续执行查询或动作。 */
    private static final Set<String> QUERY_CUES = Set.of(
            "帮我找", "推荐", "查一下", "查找", "看看", "附近", "有没有", "下单", "支付", "退款", "取消订单", "订单"
    );

    /** 可持久化环境偏好的受控别名到规范值映射。 */
    private static final Map<String, String> AMBIENCE_ALIASES = aliases(
            "适合一个人", "适合一个人",
            "适合约会", "适合约会",
            "可以办公", "可以办公",
            "有包间", "有包间",
            "亲子", "亲子",
            "安静", "安静"
    );

    /**
     * 提取单条消息中的新增、删除或清空偏好意图。
     *
     * @param message 当前用户原始输入
     * @return 归一化且去重的变更集合；未出现持久化信号时返回空提取结果
     */
    public Extraction extract(String message) {
        String text = normalize(message);
        if (text.isEmpty()) {
            return Extraction.none();
        }
        if (CLEAR_ALL.matcher(text).find()) {
            return new Extraction(List.of(), List.of(), true, isMemoryOnly(text));
        }

        // 普通“帮我找火锅”等查询不进入长期记忆，必须先通过持久化信号门槛。
        boolean durableCue = DURABLE_CUE.matcher(text).find();
        boolean directLikeCue = DIRECT_LIKE_CUE.matcher(text).find();
        boolean durable = durableCue || directLikeCue;
        if (!durable) {
            return Extraction.none();
        }

        Map<String, ExplicitPreference> preferences = new LinkedHashMap<>();
        Map<String, PreferenceRemoval> removals = new LinkedHashMap<>();
        extractPreferredCategories(text, preferences, removals);
        extractAliases(text, AMBIENCE_ALIASES, PreferenceType.PREFERRED_AMBIENCE, preferences);
        if (durableCue) {
            addNumeric(text, PRICE, 1, 10_000, PreferenceType.MAX_AVG_PRICE, "singleton", preferences);
            addScore(text, preferences);
            addDistance(text, preferences);
            if (VOUCHER_FALSE.matcher(text).find()) {
                add(preferences, new ExplicitPreference(PreferenceType.PREFER_VOUCHERS, "singleton", "false"));
            } else if (VOUCHER_TRUE.matcher(text).find()) {
                add(preferences, new ExplicitPreference(PreferenceType.PREFER_VOUCHERS, "singleton", "true"));
            }
        }
        return new Extraction(
                List.copyOf(preferences.values()),
                List.copyOf(removals.values()),
                false,
                isMemoryOnly(text)
        );
    }

    /** 分别提取明确喜欢的分类与带删除语义的排除分类。 */
    private void extractPreferredCategories(
            String text,
            Map<String, ExplicitPreference> target,
            Map<String, PreferenceRemoval> removals
    ) {
        PreferenceVocabulary.CategorySelection selection = PreferenceVocabulary.categoriesIn(text);
        for (String category : selection.included()) {
            if (hasCategoryPreferencePrefix(text, category)) {
                add(target, new ExplicitPreference(
                        PreferenceType.PREFERRED_CATEGORY,
                        category,
                        category
                ));
            }
        }
        for (String category : selection.excluded()) {
            if (hasCategoryRemovalCue(text, category)) {
                PreferenceRemoval removal = new PreferenceRemoval(PreferenceType.PREFERRED_CATEGORY, category);
                removals.put(removal.type().name() + ":" + removal.key(), removal);
            }
        }
    }

    private boolean hasCategoryPreferencePrefix(String text, String category) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : PreferenceVocabulary.categoryAliases().entrySet()) {
            if (!entry.getValue().equals(category)) {
                continue;
            }
            String alias = entry.getKey().toLowerCase(Locale.ROOT);
            int fromIndex = 0;
            while (fromIndex < lower.length()) {
                int position = lower.indexOf(alias, fromIndex);
                if (position < 0) {
                    break;
                }
                if (!PreferenceVocabulary.isCategoryNegated(lower, position, alias.length())
                        && hasPreferencePrefix(lower, position)) {
                    return true;
                }
                fromIndex = position + Math.max(1, alias.length());
            }
        }
        return false;
    }

    private boolean hasCategoryRemovalCue(String text, String category) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : PreferenceVocabulary.categoryAliases().entrySet()) {
            if (!entry.getValue().equals(category)) {
                continue;
            }
            String alias = entry.getKey().toLowerCase(Locale.ROOT);
            int fromIndex = 0;
            while (fromIndex < lower.length()) {
                int position = lower.indexOf(alias, fromIndex);
                if (position < 0) {
                    break;
                }
                if (PreferenceVocabulary.isCategoryNegated(lower, position, alias.length())
                        && (hasPreferencePrefix(lower, position) || hasCorrectionPrefix(lower, position))) {
                    return true;
                }
                fromIndex = position + Math.max(1, alias.length());
            }
        }
        return false;
    }

    private void extractAliases(
            String text,
            Map<String, String> aliases,
            PreferenceType type,
            Map<String, ExplicitPreference> target
    ) {
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> captured = new LinkedHashSet<>();
        aliases.forEach((alias, canonical) -> {
            int position = lower.indexOf(alias.toLowerCase(Locale.ROOT));
            if (position < 0 || isNegated(lower, position) || !hasPreferencePrefix(lower, position)) {
                return;
            }
            if (captured.add(canonical)) {
                add(target, new ExplicitPreference(type, canonical, canonical));
            }
        });
    }

    private boolean isNegated(String text, int position) {
        String prefix = text.substring(Math.max(0, position - 10), position);
        return prefix.matches(".*(?:不喜欢|不爱吃|不要|避免|别推荐|不需要)\\s*");
    }

    private boolean hasPreferencePrefix(String text, int position) {
        String prefix = text.substring(Math.max(0, position - 16), position);
        return PREFERENCE_PREFIX.matcher(prefix).matches();
    }

    private boolean hasCorrectionPrefix(String text, int position) {
        String prefix = text.substring(Math.max(0, position - 16), position);
        return CORRECTION_PREFIX.matcher(prefix).matches();
    }

    /** 提取受上下界约束的整数偏好，越界值直接忽略。 */
    private void addNumeric(
            String text,
            Pattern pattern,
            int min,
            int max,
            PreferenceType type,
            String key,
            Map<String, ExplicitPreference> target
    ) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return;
        }
        int value = Integer.parseInt(matcher.group(1));
        if (value >= min && value <= max) {
            add(target, new ExplicitPreference(type, key, String.valueOf(value)));
        }
    }

    private void addScore(String text, Map<String, ExplicitPreference> target) {
        Matcher matcher = SCORE.matcher(text);
        if (!matcher.find()) {
            return;
        }
        BigDecimal score = new BigDecimal(matcher.group(1)).setScale(1, RoundingMode.HALF_UP);
        if (score.compareTo(BigDecimal.ZERO) >= 0 && score.compareTo(BigDecimal.valueOf(5)) <= 0) {
            add(target, new ExplicitPreference(PreferenceType.MIN_SCORE, "singleton", score.toPlainString()));
        }
    }

    /** 将米/公里统一换算为整数米并应用合理范围。 */
    private void addDistance(String text, Map<String, ExplicitPreference> target) {
        Matcher matcher = DISTANCE.matcher(text);
        if (!matcher.find()) {
            return;
        }
        BigDecimal amount = new BigDecimal(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        int meters = unit.equals("米") || unit.equals("m")
                ? amount.setScale(0, RoundingMode.HALF_UP).intValue()
                : amount.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP).intValue();
        if (meters >= 100 && meters <= 50_000) {
            add(target, new ExplicitPreference(
                    PreferenceType.MAX_DISTANCE_METERS,
                    "singleton",
                    String.valueOf(meters)
            ));
        }
    }

    private void add(Map<String, ExplicitPreference> target, ExplicitPreference preference) {
        target.put(preference.type().name() + ":" + preference.key(), preference);
    }

    /** @return 本轮只要求管理记忆、不包含需要继续执行的查询或动作时为 true */
    private boolean isMemoryOnly(String text) {
        return QUERY_CUES.stream().noneMatch(text::contains);
    }

    /** 清理控制字符并限制扫描长度，控制正则成本和持久化输入范围。 */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300);
    }

    private static Map<String, String> aliases(String... values) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            aliases.put(values[index], values[index + 1]);
        }
        return aliases;
    }

    /**
     * 一次显式偏好解析结果。
     *
     * @param preferences 待插入或更新的偏好
     * @param removals 待按规范键删除的偏好
     * @param clearAll 是否清除当前用户全部偏好
     * @param memoryOnly 是否可在写入后直接回答而无需调用 Agent
     */
    public record Extraction(
            List<ExplicitPreference> preferences,
            List<PreferenceRemoval> removals,
            boolean clearAll,
            boolean memoryOnly
    ) {
        public Extraction {
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
            removals = removals == null ? List.of() : List.copyOf(removals);
        }

        public static Extraction none() {
            return new Extraction(List.of(), List.of(), false, false);
        }

        /** @return 至少包含一种持久化变更意图时为 true */
        public boolean hasMutation() {
            return clearAll || !preferences.isEmpty() || !removals.isEmpty();
        }
    }

    /**
     * 按类型和规范键定位的偏好删除请求。
     *
     * @param type 偏好类型
     * @param key 与保存时一致的去重键
     */
    public record PreferenceRemoval(PreferenceType type, String key) {
    }
}
