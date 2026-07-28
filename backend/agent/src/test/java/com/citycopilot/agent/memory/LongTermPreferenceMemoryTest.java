package com.citycopilot.agent.memory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermPreferenceMemoryTest {

    @Test
    void persistsAcrossConversationsAndIsolatesUsers() {
        FakeStore store = new FakeStore();
        LongTermPreferenceMemory memory = memory(store);

        LongTermPreferenceMemory.Mutation mutation = memory.applyExplicitMessage(
                7L,
                "请记住以后默认我喜欢火锅，人均100元以内，有券优先"
        );

        assertEquals(3, mutation.savedCount());
        Map<String, Object> user7 = values(memory.promptContext(7L));
        assertEquals(List.of("火锅"), user7.get("preferredCategories"));
        assertEquals(100, user7.get("maxAvgPrice"));
        assertEquals(true, user7.get("preferVouchers"));
        assertTrue(memory.promptContext(8L).isEmpty());
        assertFalse(memory.applyExplicitMessage(null, "请记住我喜欢火锅").changed());
    }

    @Test
    void updatesSingletonAndSupportsOwnedDeletion() {
        FakeStore store = new FakeStore();
        LongTermPreferenceMemory memory = memory(store);
        memory.applyExplicitMessage(7L, "请记住以后默认人均100元以内");
        memory.applyExplicitMessage(7L, "请记住以后默认人均80元以内");

        List<UserPreference> preferences = memory.list(7L);
        assertEquals(1, preferences.size());
        assertEquals("80", preferences.get(0).value());
        assertEquals(2, preferences.get(0).mentionCount());
        assertFalse(memory.forget(8L, preferences.get(0).id()));
        assertTrue(memory.forget(7L, preferences.get(0).id()));
        assertTrue(memory.list(7L).isEmpty());
    }

    @Test
    void degradesReadsButSurfacesExplicitWriteFailures() {
        UserPreferenceStore failing = new UserPreferenceStore() {
            @Override
            public void upsert(long userId, ExplicitPreference preference) {
                throw new IllegalStateException("db down");
            }

            @Override
            public List<UserPreference> list(long userId, int limit) {
                throw new IllegalStateException("db down");
            }

            @Override
            public boolean delete(long userId, long preferenceId) {
                throw new IllegalStateException("db down");
            }

            @Override
            public int deleteAll(long userId) {
                throw new IllegalStateException("db down");
            }
        };
        LongTermPreferenceMemory memory = memory(failing);

        assertTrue(memory.promptContext(7L).isEmpty());
        assertThrows(
                PreferenceMemoryWriteException.class,
                () -> memory.applyExplicitMessage(7L, "请记住我喜欢火锅")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> values(Map<String, Object> context) {
        return (Map<String, Object>) context.get("values");
    }

    private LongTermPreferenceMemory memory(UserPreferenceStore store) {
        return new LongTermPreferenceMemory(
                store,
                new ExplicitPreferenceExtractor(),
                new SimpleMeterRegistry()
        );
    }

    private static final class FakeStore implements UserPreferenceStore {
        private final AtomicLong ids = new AtomicLong();
        private final Map<String, Stored> values = new LinkedHashMap<>();

        @Override
        public void upsert(long userId, ExplicitPreference preference) {
            String key = userId + ":" + preference.type() + ":" + preference.key();
            Stored existing = values.get(key);
            if (existing == null) {
                long id = ids.incrementAndGet();
                Instant now = Instant.now();
                values.put(key, new Stored(
                        id,
                        userId,
                        preference.type(),
                        preference.value(),
                        1,
                        now,
                        now
                ));
                return;
            }
            values.put(key, new Stored(
                    existing.id(),
                    userId,
                    preference.type(),
                    preference.value(),
                    existing.mentionCount() + 1,
                    existing.createdAt(),
                    Instant.now()
            ));
        }

        @Override
        public List<UserPreference> list(long userId, int limit) {
            List<UserPreference> result = new ArrayList<>();
            values.values().stream()
                    .filter(value -> value.userId() == userId)
                    .sorted((left, right) -> right.updatedAt().compareTo(left.updatedAt()))
                    .limit(limit)
                    .forEach(value -> result.add(value.preference()));
            return result;
        }

        @Override
        public boolean delete(long userId, long preferenceId) {
            return values.entrySet().removeIf(entry ->
                    entry.getValue().userId() == userId && entry.getValue().id() == preferenceId
            );
        }

        @Override
        public int deleteAll(long userId) {
            int before = values.size();
            values.entrySet().removeIf(entry -> entry.getValue().userId() == userId);
            return before - values.size();
        }
    }

    private record Stored(
            long id,
            long userId,
            PreferenceType type,
            String value,
            int mentionCount,
            Instant createdAt,
            Instant updatedAt
    ) {
        private UserPreference preference() {
            return new UserPreference(id, userId, type, value, mentionCount, createdAt, updatedAt);
        }
    }
}
