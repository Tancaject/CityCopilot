package com.citycopilot.core.community;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.citycopilot.core.utils.RedisConstants.BLOG_LIKED_KEY;

/** Offline, resumable import. Stopping every old writer is a prerequisite, not a Redis/SQL atomicity claim. */
public final class LegacyCommunityLikesMigration {
    private static final int BATCH_SIZE = 500;
    private static final String IMPORT_LOCK = "citycopilot-community-likes-import";
    private final Connection db;
    private final RedisCommands<String, String> redis;
    private final long latestAllowedTime = System.currentTimeMillis();

    private LegacyCommunityLikesMigration(Connection db, RedisCommands<String, String> redis) {
        this.db = db;
        this.redis = redis;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage: LegacyCommunityLikesMigration [--check|--apply] (default: --check). "
                    + "Requires CITYCOPILOT_DB_URL, CITYCOPILOT_REDIS_HOST and CITYCOPILOT_REDIS_PORT. "
                    + "Stop all old Core writers first. See docs/community-facts.md.");
            return;
        }
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--check") && !args[0].equals("--apply"))) {
            throw new IllegalArgumentException("Expected --check or --apply; use --help for the offline import procedure.");
        }
        boolean apply = args.length == 1 && args[0].equals("--apply");
        Map<String, String> env = System.getenv();
        String url = required(env, "CITYCOPILOT_DB_URL");
        String host = required(env, "CITYCOPILOT_REDIS_HOST");
        int port = Integer.parseInt(required(env, "CITYCOPILOT_REDIS_PORT"));
        RedisURI.Builder address = RedisURI.Builder.redis(host, port)
                .withDatabase(Integer.parseInt(env.getOrDefault("CITYCOPILOT_REDIS_DATABASE", "0")))
                .withTimeout(Duration.ofSeconds(10));
        String password = env.getOrDefault("CITYCOPILOT_REDIS_PASSWORD", "");
        if (!password.isEmpty()) address.withPassword(password.toCharArray());
        RedisClient client = RedisClient.create(address.build());
        try (Connection db = DriverManager.getConnection(url, env.getOrDefault("CITYCOPILOT_DB_USERNAME", "root"),
                env.getOrDefault("CITYCOPILOT_DB_PASSWORD", ""));
             StatefulRedisConnection<String, String> connection = client.connect()) {
            if (connection.sync().info("cluster").contains("cluster_enabled:1")) {
                throw rejected("This importer requires the standalone Redis configured by Core; a single cluster node is not a complete source.");
            }
            Outcome result = run(db, connection.sync(), apply);
            System.out.printf("%s notes=%d likes=%d ready=%s%n",
                    result.alreadyReady() ? "ALREADY_READY" : apply ? "APPLY_OK" : "CHECK_OK",
                    result.notes(), result.likes(), result.ready());
        } finally {
            client.shutdown();
        }
    }

    static Outcome run(Connection db, RedisCommands<String, String> redis, boolean apply) throws SQLException {
        if (!db.getAutoCommit()) throw new IllegalArgumentException("The import requires its own auto-commit connection.");
        if (db.getCatalog() == null || db.getCatalog().isBlank()) {
            throw new IllegalArgumentException("CITYCOPILOT_DB_URL must explicitly select the Core database.");
        }
        LegacyCommunityLikesMigration migration = new LegacyCommunityLikesMigration(db, redis);
        if (!apply) return migration.ready() ? new Outcome(0, 0, true, true) : migration.inspect(false, false);
        migration.acquireLock();
        try {
            if (migration.ready()) return new Outcome(0, 0, true, true);
            migration.inspect(false, false);
            migration.inspect(true, true);
            Outcome verified = migration.inspect(false, true);
            // One statement checks every persisted counter and publishes the cutover together.
            try (var statement = db.createStatement()) {
                int changed = statement.executeUpdate("""
                        UPDATE community_facts_cutover SET ready=1,completed_at=UTC_TIMESTAMP(3)
                        WHERE id=1 AND ready=0 AND NOT EXISTS (
                            SELECT 1 FROM note n LEFT JOIN
                              (SELECT note_id,COUNT(*) AS total FROM note_like GROUP BY note_id) l ON l.note_id=n.id
                            WHERE n.liked IS NULL OR n.liked<>COALESCE(l.total,0))
                        """);
                if (changed != 1) throw rejected("SQL counts changed before cutover. Keep writers stopped and rerun --check.");
            }
            return new Outcome(verified.notes(), verified.likes(), true, false);
        } finally {
            try (var statement = db.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, IMPORT_LOCK);
                statement.executeQuery().close();
            }
        }
    }

    private Outcome inspect(boolean insert, boolean requireComplete) throws SQLException {
        verifySourceKeys();
        long notes = 0;
        long likes = 0;
        long lastId = 0;
        while (true) {
            List<Note> page = notesAfter(lastId);
            if (page.isEmpty()) break;
            for (Note note : page) {
                String key = BLOG_LIKED_KEY + note.id();
                long cardinality = redis.zcard(key);
                if (cardinality != note.likes()) {
                    throw rejected("note " + note.id() + ": MySQL liked=" + note.likes() + ", Redis ZCARD=" + cardinality
                            + ". Restore/resolve the source evidence; counters will not be rewritten.");
                }
                long matchedTarget = 0;
                for (long offset = 0; offset < cardinality; offset += BATCH_SIZE) {
                    List<ScoredValue<String>> batch = redis.zrangeWithScores(key, offset, Math.min(offset + BATCH_SIZE, cardinality) - 1);
                    if (batch.size() != Math.min(BATCH_SIZE, cardinality - offset)) {
                        throw rejected("note " + note.id() + ": Redis changed while reading. Stop old writers before retrying.");
                    }
                    List<Like> validated = new ArrayList<>(batch.size());
                    for (ScoredValue<String> source : batch) {
                        long userId = positiveId(source.getValue(), "like member for note " + note.id());
                        double score = source.getScore();
                        if (!Double.isFinite(score) || score <= 0 || score != Math.rint(score) || score > latestAllowedTime) {
                            throw rejected("note " + note.id() + ", user " + userId + ": expected a valid past Unix millisecond score.");
                        }
                        Like like = new Like(note.id(), userId, (long) score);
                        requireUser(userId);
                        if (existingMatches(like)) matchedTarget++;
                        validated.add(like);
                    }
                    if (insert) insertBatch(validated);
                }
                long targetCount = countTarget(note.id());
                long expectedTarget = insert || requireComplete ? cardinality : matchedTarget;
                if (targetCount != expectedTarget || (requireComplete && !insert && matchedTarget != cardinality)) {
                    throw rejected("note " + note.id() + ": target note_like rows do not match the validated Redis source. "
                            + "Do not delete either source; inspect the conflicting migration state.");
                }
                if (redis.zcard(key) != cardinality) throw rejected("note " + note.id() + ": Redis changed during validation.");
                notes++;
                likes += cardinality;
                lastId = note.id();
            }
        }
        return new Outcome(notes, likes, false, false);
    }

    private void verifySourceKeys() throws SQLException {
        ScanCursor cursor = ScanCursor.INITIAL;
        do {
            KeyScanCursor<String> page = redis.scan(cursor, ScanArgs.Builder.matches(BLOG_LIKED_KEY + "*").limit(BATCH_SIZE));
            for (String key : page.getKeys()) {
                long noteId = positiveId(key.substring(BLOG_LIKED_KEY.length()), "Redis key " + key);
                try (var statement = db.prepareStatement("SELECT id FROM note WHERE id=?")) {
                    statement.setLong(1, noteId);
                    try (var row = statement.executeQuery()) {
                        if (!row.next()) throw rejected("Redis key " + key + " has no note. Resolve the orphan from backups before cutover.");
                    }
                }
            }
            cursor = page;
        } while (!cursor.isFinished());
    }

    private List<Note> notesAfter(long id) throws SQLException {
        try (var statement = db.prepareStatement("SELECT id,liked FROM note WHERE id>? ORDER BY id LIMIT " + BATCH_SIZE)) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                List<Note> notes = new ArrayList<>();
                while (rows.next()) {
                    long noteId = rows.getLong(1);
                    long count = rows.getLong(2);
                    if (rows.wasNull()) throw rejected("note " + noteId + ": liked is NULL; resolve the original counter before import.");
                    notes.add(new Note(noteId, count));
                }
                return notes;
            }
        }
    }

    private void requireUser(long userId) throws SQLException {
        try (var statement = db.prepareStatement("SELECT id FROM user_account WHERE id=?")) {
            statement.setLong(1, userId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw rejected("Like user " + userId + " does not exist in user_account. Restore/resolve the identity before cutover.");
            }
        }
    }

    private boolean existingMatches(Like like) throws SQLException {
        try (var statement = db.prepareStatement("SELECT liked_at FROM note_like WHERE note_id=? AND user_id=?")) {
            statement.setLong(1, like.noteId());
            statement.setLong(2, like.userId());
            try (var row = statement.executeQuery()) {
                if (!row.next()) return false;
                if (row.getLong(1) != like.likedAt()) {
                    throw rejected("note " + like.noteId() + ", user " + like.userId() + ": target timestamp conflicts with Redis. Nothing is overwritten.");
                }
                return true;
            }
        }
    }

    private long countTarget(long noteId) throws SQLException {
        try (var statement = db.prepareStatement("SELECT COUNT(*) FROM note_like WHERE note_id=?")) {
            statement.setLong(1, noteId);
            try (var row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void insertBatch(List<Like> likes) throws SQLException {
        db.setAutoCommit(false);
        try (var statement = db.prepareStatement("INSERT INTO note_like(note_id,user_id,liked_at) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE liked_at=note_like.liked_at")) {
            for (Like like : likes) {
                statement.setLong(1, like.noteId());
                statement.setLong(2, like.userId());
                statement.setLong(3, like.likedAt());
                statement.addBatch();
            }
            statement.executeBatch();
            db.commit();
        } catch (SQLException | RuntimeException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private boolean ready() throws SQLException {
        try (var statement = db.createStatement(); var row = statement.executeQuery("SELECT ready FROM community_facts_cutover WHERE id=1")) {
            if (!row.next()) throw rejected("Missing community_facts_cutover row. Apply Core Flyway V5 before running this importer.");
            return row.getBoolean(1);
        }
    }

    private void acquireLock() throws SQLException {
        try (var statement = db.prepareStatement("SELECT GET_LOCK(?,0)")) {
            statement.setString(1, IMPORT_LOCK);
            try (var row = statement.executeQuery()) {
                row.next();
                if (row.getInt(1) != 1) throw rejected("Another community likes importer holds the MySQL lock; wait for it to finish.");
            }
        }
    }

    private static long positiveId(String value, String location) {
        try {
            long id = Long.parseLong(value);
            if (id > 0 && Long.toString(id).equals(value)) return id;
        } catch (NumberFormatException ignored) {
            // The diagnostic identifies the legacy data that needs operator attention.
        }
        throw rejected(location + ": expected a canonical positive Java Long identifier.");
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " must be explicitly set; see docs/community-facts.md.");
        return value;
    }

    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException("Community likes cutover refused: " + reason + " See docs/community-facts.md.");
    }

    record Outcome(long notes, long likes, boolean ready, boolean alreadyReady) { }
    private record Note(long id, long likes) { }
    private record Like(long noteId, long userId, long likedAt) { }
}
