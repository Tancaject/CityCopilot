package com.citycopilot.agent.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 长期偏好提取与请求时参数补全共用的受控分类词表。
 *
 * <p>统一别名、否定和纠正语义，确保“记忆写入”与“当前请求覆盖记忆”采用相同分类判断。</p>
 */
public final class PreferenceVocabulary {
    /** 判断否定/肯定上下文时使用的分句边界。 */
    private static final String CLAUSE_BOUNDARIES = "，。！？,!?；;";
    /** 分类词之前的否定表达。 */
    private static final Pattern PREFIX_NEGATED_CATEGORY = Pattern.compile(
            ".*(?:不喜欢|不爱吃|不想吃|不想要|不能吃|吃不了|不吃|不要|避免|不推荐|别推荐|不需要|不考虑|排除|除了|不是|拒绝|忌口|忌)"
                    + "[^" + CLAUSE_BOUNDARIES + "]{0,4}$"
    );
    /** 分类词之后的否定或纠正表达。 */
    private static final Pattern SUFFIX_NEGATED_CATEGORY = Pattern.compile(
            "^[^" + CLAUSE_BOUNDARIES + "]{0,4}"
                    + "(?:不喜欢|不想吃|不想要|不能吃|吃不了|不吃|不要|不考虑|不推荐|排除|算了|过敏|忌口|不行)"
    );
    /** 被先前排除后可重新纳入的明确肯定表达。 */
    private static final Pattern PREFIX_POSITIVE_CATEGORY = Pattern.compile(
            ".*(?:喜欢|偏好|爱吃|想吃|想要|要吃|推荐|优先|改吃|就吃|可以吃|能吃)"
                    + "[^" + CLAUSE_BOUNDARIES + "]{0,4}$"
    );
    /** 中英文别名到规范分类值的不可变映射。 */
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

    private PreferenceVocabulary() {
    }

    static Map<String, String> categoryAliases() {
        return CATEGORY_ALIASES;
    }

    /**
     * 按消息出现顺序解析分类，并处理否定与后续明确纠正。
     *
     * @param message 当前用户文本
     * @return 保序的包含分类与去重的排除分类
     */
    public static CategorySelection categoriesIn(String message) {
        String text = normalize(message);
        if (text.isEmpty()) {
            return CategorySelection.empty();
        }
        List<CategoryMention> mentions = new ArrayList<>();
        CATEGORY_ALIASES.forEach((alias, canonical) -> {
            String normalizedAlias = alias.toLowerCase(Locale.ROOT);
            int fromIndex = 0;
            while (fromIndex < text.length()) {
                int position = text.indexOf(normalizedAlias, fromIndex);
                if (position < 0) {
                    break;
                }
                mentions.add(new CategoryMention(
                        position,
                        normalizedAlias.length(),
                        canonical,
                        isCategoryNegated(text, position, normalizedAlias.length())
                ));
                fromIndex = position + Math.max(1, normalizedAlias.length());
            }
        });
        // 同一位置优先匹配更长别名，避免“咖啡店”再被短词“咖啡”重复解释。
        mentions.sort(Comparator
                .comparingInt(CategoryMention::position)
                .thenComparing(Comparator.comparingInt(CategoryMention::aliasLength).reversed()));

        LinkedHashSet<String> included = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        for (CategoryMention mention : mentions) {
            if (mention.negated()) {
                included.remove(mention.canonical());
                excluded.add(mention.canonical());
            } else if (!excluded.contains(mention.canonical())
                    || hasExplicitPositiveCue(text, mention.position())) {
                excluded.remove(mention.canonical());
                included.add(mention.canonical());
            }
        }
        return new CategorySelection(List.copyOf(included), Set.copyOf(excluded));
    }

    /**
     * 将模型、记忆或用户传入的分类转换为受控规范值。
     *
     * @return 已知别名或规范值；词表外内容为空
     */
    public static Optional<String> canonicalCategory(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> entry : CATEGORY_ALIASES.entrySet()) {
            if (normalized.equals(entry.getKey().toLowerCase(Locale.ROOT))
                    || normalized.equals(entry.getValue().toLowerCase(Locale.ROOT))) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    static boolean isCategoryNegated(String text, int position, int aliasLength) {
        String prefix = text.substring(Math.max(0, position - 16), position);
        String suffix = text.substring(Math.min(text.length(), position + aliasLength));
        return PREFIX_NEGATED_CATEGORY.matcher(prefix).matches()
                || SUFFIX_NEGATED_CATEGORY.matcher(suffix).find();
    }

    private static boolean hasExplicitPositiveCue(String text, int position) {
        String prefix = text.substring(Math.max(0, position - 16), position);
        return PREFIX_POSITIVE_CATEGORY.matcher(prefix).matches();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> aliases(String... values) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            aliases.put(values[index], values[index + 1]);
        }
        return Collections.unmodifiableMap(aliases);
    }

    /**
     * 当前请求中的分类选择结果。
     *
     * @param included 明确希望的规范分类，保留表达顺序
     * @param excluded 明确排除的规范分类
     */
    public record CategorySelection(List<String> included, Set<String> excluded) {
        public CategorySelection {
            included = included == null ? List.of() : List.copyOf(included);
            excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
        }

        public static CategorySelection empty() {
            return new CategorySelection(List.of(), Set.of());
        }
    }

    /** 单次别名命中的位置、长度、规范值和否定状态。 */
    private record CategoryMention(int position, int aliasLength, String canonical, boolean negated) {
    }
}
