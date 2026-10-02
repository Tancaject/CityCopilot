package com.citycopilot.core.community;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.sync.RedisCommands;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Creates only random databases on the explicitly supplied local temporary MySQL server. */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_MIGRATION_TEST_URL", matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")
class LegacyCommunityLikesMigrationIT {
    private static final String SERVER = System.getenv("CITYCOPILOT_MIGRATION_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_PASSWORD", "");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final long LIKE_TIME = 1_600_000_000_000L;
    private final Map<String, List<ScoredValue<String>>> source = new LinkedHashMap<>();
    private Connection admin;
    private Connection db;
    private String database;
    private Flyway flyway;
    private RedisCommands<String, String> redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void createIsolatedDatabase() throws Exception {
        database = "cc_community_" + UUID.randomUUID().toString().replace("-", "");
        admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD);
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        db = DriverManager.getConnection(SERVER + database + OPTIONS, USER, PASSWORD);
        flyway = Flyway.configure().dataSource(SERVER + database + OPTIONS, USER, PASSWORD)
                .locations("filesystem:" + Path.of("../..").toAbsolutePath().normalize().resolve("deploy/mysql/core"))
                .cleanDisabled(true).load();
        Flyway.configure().configuration(flyway.getConfiguration()).target("4").load().migrate();
        execute("INSERT INTO user_account(id,phone,nick_name) VALUES (1,'10000000001','one'),(2,'10000000002','two'),(3,'10000000003','three')");
        execute("INSERT INTO note(id,shop_id,user_id,title,images,content,liked) VALUES (1,1,1,'legacy','image','content',2)");
        source.put("blog:liked:1", List.of(ScoredValue.just(LIKE_TIME, "2"), ScoredValue.just(LIKE_TIME + 10, "3")));
        redis = mock(RedisCommands.class);
        when(redis.scan(any(ScanCursor.class), any(ScanArgs.class))).thenAnswer(call -> {
            KeyScanCursor<String> page = new KeyScanCursor<>();
            page.setCursor("0");
            page.setFinished(true);
            page.getKeys().addAll(source.keySet());
            return page;
        });
        when(redis.zcard(anyString())).thenAnswer(call -> (long) source.getOrDefault(call.getArgument(0), List.of()).size());
        when(redis.zrangeWithScores(anyString(), anyLong(), anyLong())).thenAnswer(call -> {
            List<ScoredValue<String>> values = source.getOrDefault(call.getArgument(0), List.of());
            int first = Math.toIntExact(call.getArgument(1, Long.class));
            int afterLast = Math.min(values.size(), Math.toIntExact(call.getArgument(2, Long.class)) + 1);
            return new ArrayList<>(values.subList(first, afterLast));
        });
    }

    @AfterEach
    void dropOnlyOwnedDatabase() throws Exception {
        if (db != null) db.close();
        if (admin != null) {
            try (var statement = admin.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            } finally {
                admin.close();
            }
        }
    }

    @Test
    void checkIsReadOnlyAndApplyPreservesEveryLikeAndPublishesReadiness() throws Exception {
        execute("INSERT INTO user_follow(id,user_id,follow_user_id) VALUES (10,1,2),(11,1,2)");
        flyway.migrate();
        assertEquals(10, scalar("SELECT id FROM user_follow WHERE user_id=1 AND follow_user_id=2"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO user_follow(user_id,follow_user_id) VALUES (1,2)"));
        assertFalse(isReady());
        assertThrows(IllegalStateException.class, () -> readiness().afterPropertiesSet());

        var checked = LegacyCommunityLikesMigration.run(db, redis, false);
        assertEquals(2, checked.likes());
        assertFalse(checked.ready());
        assertEquals(0, scalar("SELECT COUNT(*) FROM note_like"));
        assertFalse(isReady());

        var applied = LegacyCommunityLikesMigration.run(db, redis, true);
        assertTrue(applied.ready());
        assertEquals(2, scalar("SELECT COUNT(*) FROM note_like"));
        assertEquals(2, scalar("SELECT liked FROM note WHERE id=1"));
        assertEquals(LIKE_TIME, scalar("SELECT liked_at FROM note_like WHERE note_id=1 AND user_id=2"));
        assertDoesNotThrow(() -> readiness().afterPropertiesSet());
        clearInvocations(redis);
        assertTrue(LegacyCommunityLikesMigration.run(db, redis, true).alreadyReady());
        verifyNoInteractions(redis);
        execute("DELETE FROM note WHERE id=1");
        assertEquals(0, scalar("SELECT COUNT(*) FROM note_like"));
    }

    @Test
    void resumesAnInterruptedImportWithoutOverwritingItsTimestamp() throws Exception {
        flyway.migrate();
        execute("INSERT INTO note_like(note_id,user_id,liked_at) VALUES (1,2," + LIKE_TIME + ")");
        assertFalse(LegacyCommunityLikesMigration.run(db, redis, false).ready());
        assertEquals(1, scalar("SELECT COUNT(*) FROM note_like"));
        assertTrue(LegacyCommunityLikesMigration.run(db, redis, true).ready());
        assertEquals(2, scalar("SELECT COUNT(*) FROM note_like"));
        assertEquals(LIKE_TIME, scalar("SELECT liked_at FROM note_like WHERE user_id=2"));
    }

    @Test
    void importsAcrossBatchBoundaryAndEveryRedisScanPage() throws Exception {
        List<ScoredValue<String>> manyLikes = new ArrayList<>();
        try (var statement = db.prepareStatement("INSERT INTO user_account(id,phone,nick_name) VALUES (?,?,?)")) {
            for (int index = 0; index < 501; index++) {
                long userId = 1000L + index;
                statement.setLong(1, userId);
                statement.setString(2, String.format("199%08d", userId));
                statement.setString(3, "migration-user");
                statement.addBatch();
                manyLikes.add(ScoredValue.just(LIKE_TIME + index, Long.toString(userId)));
            }
            statement.executeBatch();
        }
        execute("UPDATE note SET liked=501 WHERE id=1");
        execute("INSERT INTO note(id,shop_id,user_id,title,images,content,liked) VALUES (2,1,1,'second','image','content',1)");
        source.put("blog:liked:1", manyLikes);
        source.put("blog:liked:2", List.of(ScoredValue.just(LIKE_TIME, "2")));
        when(redis.scan(any(ScanCursor.class), any(ScanArgs.class))).thenAnswer(call -> {
            boolean firstPage = call.getArgument(0, ScanCursor.class).getCursor().equals("0");
            KeyScanCursor<String> page = new KeyScanCursor<>();
            page.setCursor(firstPage ? "1" : "0");
            page.setFinished(!firstPage);
            page.getKeys().add(firstPage ? "blog:liked:1" : "blog:liked:2");
            return page;
        });
        flyway.migrate();

        var result = LegacyCommunityLikesMigration.run(db, redis, true);

        assertTrue(result.ready());
        assertEquals(2, result.notes());
        assertEquals(502, result.likes());
        assertEquals(501, scalar("SELECT COUNT(*) FROM note_like WHERE note_id=1"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM note_like WHERE note_id=2"));
        assertEquals(LIKE_TIME + 500, scalar("SELECT liked_at FROM note_like WHERE note_id=1 AND user_id=1500"));
        verify(redis, times(6)).scan(any(ScanCursor.class), any(ScanArgs.class));
        verify(redis, times(3)).zrangeWithScores("blog:liked:1", 0, 499);
        verify(redis, times(3)).zrangeWithScores("blog:liked:1", 500, 500);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-key", "unknown-user", "fractional-time", "future-time", "noncanonical-user", "orphan-key"})
    void rejectsLostOrInvalidSourceWithoutWriting(String invalid) throws Exception {
        flyway.migrate();
        switch (invalid) {
            case "missing-key" -> source.clear();
            case "unknown-user" -> source.put("blog:liked:1", List.of(ScoredValue.just(LIKE_TIME, "2"), ScoredValue.just(LIKE_TIME, "99")));
            case "fractional-time" -> source.put("blog:liked:1", List.of(ScoredValue.just(LIKE_TIME + 0.5, "2"), ScoredValue.just(LIKE_TIME, "3")));
            case "future-time" -> source.put("blog:liked:1", List.of(ScoredValue.just(System.currentTimeMillis() + 3_600_000, "2"), ScoredValue.just(LIKE_TIME, "3")));
            case "noncanonical-user" -> source.put("blog:liked:1", List.of(ScoredValue.just(LIKE_TIME, "02"), ScoredValue.just(LIKE_TIME, "3")));
            case "orphan-key" -> source.put("blog:liked:999", List.of(ScoredValue.just(LIKE_TIME, "2")));
            default -> fail("Unknown fixture");
        }
        assertThrows(IllegalStateException.class, () -> LegacyCommunityLikesMigration.run(db, redis, true));
        assertEquals(0, scalar("SELECT COUNT(*) FROM note_like"));
        assertEquals(2, scalar("SELECT liked FROM note WHERE id=1"));
        assertFalse(isReady());
    }

    @Test
    void refusesConflictingPartialTargetRatherThanReplacingIt() throws Exception {
        flyway.migrate();
        execute("INSERT INTO note_like(note_id,user_id,liked_at) VALUES (1,2," + (LIKE_TIME + 1) + ")");
        assertThrows(IllegalStateException.class, () -> LegacyCommunityLikesMigration.run(db, redis, true));
        assertEquals(LIKE_TIME + 1, scalar("SELECT liked_at FROM note_like WHERE user_id=2"));
        assertFalse(isReady());
    }

    @Test
    void unknownCountsBlockStartupAndImport() throws Exception {
        execute("UPDATE note SET liked=NULL WHERE id=1");
        flyway.migrate();
        assertFalse(isReady());
        assertThrows(IllegalStateException.class, () -> LegacyCommunityLikesMigration.run(db, redis, false));
        assertThrows(IllegalStateException.class, () -> readiness().afterPropertiesSet());
    }

    @Test
    void existingZeroCountsStillRequireSourceValidation() throws Exception {
        execute("UPDATE note SET liked=0 WHERE id=1");
        flyway.migrate();
        assertFalse(isReady());
        assertThrows(IllegalStateException.class, () -> LegacyCommunityLikesMigration.run(db, redis, true));
        source.clear();
        assertTrue(LegacyCommunityLikesMigration.run(db, redis, true).ready());
        assertEquals(0, scalar("SELECT COUNT(*) FROM note_like"));
    }

    @Test
    void onlyEmptyDatabasesAreImmediatelyReady() throws Exception {
        execute("DELETE FROM note");
        flyway.migrate();
        assertTrue(isReady());
        assertEquals(0, scalar("SELECT COUNT(*) FROM note_like"));
        assertDoesNotThrow(() -> readiness().afterPropertiesSet());
    }

    private CommunityFactsReadiness readiness() {
        return new CommunityFactsReadiness(new JdbcTemplate(new SingleConnectionDataSource(db, true)));
    }

    private boolean isReady() throws SQLException {
        return scalar("SELECT ready FROM community_facts_cutover WHERE id=1") == 1;
    }

    private void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }

    private long scalar(String sql) throws SQLException {
        try (var statement = db.createStatement(); var row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }
}
