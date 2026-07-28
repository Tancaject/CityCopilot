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

@Component
public final class ExplicitPreferenceExtractor {
    private static final Pattern DURABLE_CUE = Pattern.compile(
            "请?记住|帮我记住|以后|今后|默认|长期偏好|我的偏好|我的预算|我(?:平时|通常|一般)"
    );
    private static final Pattern DIRECT_LIKE_CUE = Pattern.compile("我(?:更|比较)?(?:喜欢|偏好|爱吃)");
    private static final Pattern CLEAR_ALL = Pattern.compile(
            "(?:忘记|清除|删除|重置).{0,8}(?:所有|全部|我的)?(?:长期)?偏好|不再记住.{0,8}偏好"
    );
    private static final Pattern PRICE = Pattern.compile(
            "(?:人均|每人预算|预算)\\s*(?:不超过|最多|控制在|为|是|大约|约)?\\s*(\\d{1,5})\\s*元?\\s*(?:以内|以下|左右|封顶)?"
    );
    private static final Pattern SCORE = Pattern.compile(
            "评分\\s*(\\d(?:\\.\\d)?)\\s*(?:以上|起|至少)"
    );
    private static final Pattern DISTANCE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米|m)\\s*(?:以内|内|之内|最多)"
    );
    private static final Pattern VOUCHER_TRUE = Pattern.compile(
            "有券优先|优惠券优先|优先.{0,6}(?:有券|优惠券)|默认.{0,6}(?:有券|优惠券)"
    );
    private static final Pattern VOUCHER_FALSE = Pattern.compile(
            "不用考虑优惠券|不需要优惠券|不优先.{0,4}(?:有券|优惠券)"
    );
    private static final Set<String> QUERY_CUES = Set.of(
            "帮我找", "推荐", "查一下", "查找", "看看", "附近", "有没有", "下单", "支付", "退款", "取消订单", "订单"
    );

    private static final Map<String, String> CATEGORY_ALIASES = aliases(
            "日本料理", "日料",
            "自助餐", "自助餐",
            "咖啡店", "咖啡",
            "hotpot", "火锅",
            "coffee", "咖啡",
            "火锅", "火锅",
            "咖啡", "咖啡",
            "川菜", "川菜",
            "粤菜", "粤菜",
            "湘菜", "湘菜",
            "日料", "日料",
            "烧烤", "烧烤",
            "甜品", "甜品",
            "西餐", "西餐",
            "中餐", "中餐",
            "面馆", "面馆",
            "小吃", "小吃",
            "茶饮", "茶饮",
            "奶茶", "奶茶",
            "烘焙", "烘焙",
            "素食", "素食",
            "海鲜", "海鲜"
    );
    private static final Map<String, String> AMBIENCE_ALIASES = aliases(
            "适合一个人", "适合一个人",
            "适合约会", "适合约会",
            "可以办公", "可以办公",
            "有包间", "有包间",
            "亲子", "亲子",
            "安静", "安静"
    );

    public Extraction extract(String message) {
        String text = normalize(message);
        if (text.isEmpty()) {
            return Extraction.none();
        }
        if (CLEAR_ALL.matcher(text).find()) {
            return new Extraction(List.of(), true, isMemoryOnly(text));
        }

        boolean durableCue = DURABLE_CUE.matcher(text).find();
        boolean directLikeCue = DIRECT_LIKE_CUE.matcher(text).find();
        boolean durable = durableCue || directLikeCue;
        if (!durable) {
            return Extraction.none();
        }

        Map<String, ExplicitPreference> preferences = new LinkedHashMap<>();
        extractAliases(text, CATEGORY_ALIASES, PreferenceType.PREFERRED_CATEGORY, preferences);
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
        return new Extraction(List.copyOf(preferences.values()), false, isMemoryOnly(text));
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
        return prefix.matches(".*(?:记住|喜欢|偏好|爱吃|默认|优先)\\s*.*");
    }

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

    private boolean isMemoryOnly(String text) {
        return QUERY_CUES.stream().noneMatch(text::contains);
    }

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

    public record Extraction(
            List<ExplicitPreference> preferences,
            boolean clearAll,
            boolean memoryOnly
    ) {
        public Extraction {
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
        }

        public static Extraction none() {
            return new Extraction(List.of(), false, false);
        }

        public boolean hasMutation() {
            return clearAll || !preferences.isEmpty();
        }
    }
}
