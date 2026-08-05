package com.citycopilot.agent.memory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class LongTermPreferenceMemory {
    private static final Logger log = LoggerFactory.getLogger(LongTermPreferenceMemory.class);
    private static final int PROMPT_PREFERENCE_LIMIT = 20;
    private static final int API_PREFERENCE_LIMIT = 50;

    private final UserPreferenceStore store;
    private final ExplicitPreferenceExtractor extractor;
    private final MeterRegistry meterRegistry;

    @Autowired
    public LongTermPreferenceMemory(
            UserPreferenceStore store,
            ExplicitPreferenceExtractor extractor,
            ObjectProvider<MeterRegistry> meterRegistry
    ) {
        this(store, extractor, meterRegistry.getIfAvailable());
    }

    LongTermPreferenceMemory(
            UserPreferenceStore store,
            ExplicitPreferenceExtractor extractor,
            MeterRegistry meterRegistry
    ) {
        this.store = store;
        this.extractor = extractor;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public Mutation applyExplicitMessage(Long userId, String message) {
        if (userId == null || userId <= 0) {
            return Mutation.none();
        }
        ExplicitPreferenceExtractor.Extraction extraction = extractor.extract(message);
        if (!extraction.hasMutation()) {
            return Mutation.none();
        }
        try {
            if (extraction.clearAll()) {
                int deleted = store.deleteAll(userId);
                record("write", "cleared");
                return Mutation.cleared(deleted, extraction.memoryOnly());
            }
            int deleted = 0;
            for (ExplicitPreferenceExtractor.PreferenceRemoval removal : extraction.removals()) {
                deleted += store.deleteByKey(userId, removal.type(), removal.key());
            }
            for (ExplicitPreference preference : extraction.preferences()) {
                store.upsert(userId, preference);
            }
            int saved = extraction.preferences().size();
            record("write", saved > 0 ? "saved" : "removed");
            return Mutation.updated(
                    saved,
                    deleted,
                    !extraction.removals().isEmpty(),
                    extraction.memoryOnly()
            );
        } catch (RuntimeException error) {
            record("write", "error");
            throw new PreferenceMemoryWriteException("LONG_TERM_PREFERENCE_WRITE_FAILED", error);
        }
    }

    public Map<String, Object> promptContext(Long userId) {
        if (userId == null || userId <= 0) {
            return Map.of();
        }
        try {
            List<UserPreference> preferences = store.list(userId, PROMPT_PREFERENCE_LIMIT);
            Map<String, Object> values = promptValues(preferences);
            if (values.isEmpty()) {
                record("read", "empty");
                return Map.of();
            }
            record("read", "success");
            return Map.of(
                    "scope", "recommendation_defaults",
                    "currentRequestOverrides", true,
                    "values", values
            );
        } catch (RuntimeException error) {
            record("read", "error");
            log.warn("Long-term preference read failed for userId={}", userId, error);
            return Map.of();
        }
    }

    public List<UserPreference> list(Long userId) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return store.list(userId, API_PREFERENCE_LIMIT);
    }

    public boolean forget(Long userId, long preferenceId) {
        if (userId == null || userId <= 0 || preferenceId <= 0) {
            return false;
        }
        return store.delete(userId, preferenceId);
    }

    public int forgetAll(Long userId) {
        if (userId == null || userId <= 0) {
            return 0;
        }
        return store.deleteAll(userId);
    }

    private Map<String, Object> promptValues(List<UserPreference> preferences) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<PreferenceType, Set<String>> multiple = new LinkedHashMap<>();
        for (UserPreference preference : preferences) {
            if (preference.type().multiple()) {
                multiple.computeIfAbsent(preference.type(), ignored -> new LinkedHashSet<>())
                        .add(preference.value());
            } else {
                values.putIfAbsent(preference.type().promptField(), typedValue(preference));
            }
        }
        multiple.forEach((type, items) -> values.put(type.promptField(), new ArrayList<>(items)));
        return values;
    }

    private Object typedValue(UserPreference preference) {
        return switch (preference.type()) {
            case MAX_AVG_PRICE, MAX_DISTANCE_METERS -> Integer.valueOf(preference.value());
            case MIN_SCORE -> Double.valueOf(preference.value());
            case PREFER_VOUCHERS -> Boolean.valueOf(preference.value());
            default -> preference.value();
        };
    }

    private void record(String operation, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("citycopilot.agent.preference.memory")
                .description("Long-term user preference memory operations")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    public record Mutation(
            int savedCount,
            int deletedCount,
            boolean memoryOnly,
            boolean changed,
            boolean removalRequested
    ) {
        public static Mutation none() {
            return new Mutation(0, 0, false, false, false);
        }

        public static Mutation updated(int saved, int deleted, boolean removalRequested, boolean memoryOnly) {
            return new Mutation(
                    saved,
                    deleted,
                    memoryOnly,
                    saved > 0 || deleted > 0 || removalRequested,
                    removalRequested
            );
        }

        public static Mutation cleared(int count, boolean memoryOnly) {
            return new Mutation(0, count, memoryOnly, true, false);
        }

        public String responseMessage() {
            if (savedCount > 0 && deletedCount > 0) {
                return "已更新长期偏好：记住 " + savedCount + " 项，移除 " + deletedCount + " 项。";
            }
            if (savedCount > 0) {
                return "已记住 " + savedCount + " 项长期偏好。后续推荐会默认参考，本轮明确条件始终优先。";
            }
            if (removalRequested && deletedCount == 0) {
                return "未找到需要移除的长期偏好，已忽略该项正向偏好。";
            }
            return deletedCount > 0
                    ? "已清除 " + deletedCount + " 项长期偏好。"
                    : "长期偏好已清空。";
        }
    }
}
