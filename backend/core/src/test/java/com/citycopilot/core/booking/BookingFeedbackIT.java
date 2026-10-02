package com.citycopilot.core.booking;

import tools.jackson.databind.json.JsonMapper;

import com.citycopilot.contract.event.BookingChangedEvent;
import com.citycopilot.core.event.InboxOutcome;
import com.citycopilot.core.event.InboxRepository;
import com.citycopilot.core.event.BookingProjectionMetrics;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Requires an explicitly supplied disposable MySQL database; no project or UI database is created or reset. */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_FEEDBACK_TEST_DB_URL", matches = "jdbc:mysql:.+")
class BookingFeedbackIT {
    private static HikariDataSource pool;
    private static JdbcTemplate sql;
    private static BookingFeedbackService feedback;
    private static BookingBehaviorProjector projector;
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().findAndAddModules().build();
    private static final String GROUP = "booking-feedback-it";
    private static Path root;

    @BeforeAll
    static void connect() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("CITYCOPILOT_FEEDBACK_TEST_DB_URL"));
        config.setUsername(System.getenv().getOrDefault("CITYCOPILOT_FEEDBACK_TEST_DB_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("CITYCOPILOT_FEEDBACK_TEST_DB_PASSWORD", ""));
        config.setMaximumPoolSize(12);
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        pool = new HikariDataSource(config);
        try (var connection = pool.getConnection()) {
            if (!connection.getCatalog().endsWith("_test")) throw new IllegalStateException("Feedback acceptance requires a disposable database ending in _test");
        }
        sql = new JdbcTemplate(pool);
        root = Path.of("../..").toAbsolutePath().normalize();
        migrate();
        migrate();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
        var policy = new BookingFeedbackPolicy(true, 30, 60, 3600000, GROUP);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var metrics = new SimpleMeterRegistry();
        feedback = new BookingFeedbackService(sql, tx, JSON, redis, policy, metrics);
        projector = new BookingBehaviorProjector(new InboxRepository(sql), feedback, policy, tx, sql, JSON,
                new BookingProjectionMetrics(metrics));
    }

    private static void migrate() {
        org.flywaydb.core.Flyway.configure().dataSource(pool)
                .locations("filesystem:" + root.resolve("deploy/mysql/core"))
                .cleanDisabled(true).load().migrate();
    }

    @BeforeEach
    void reset() {
        sql.execute("DROP TRIGGER IF EXISTS booking_feedback_it_reject_fact");
        for (String table : List.of("user_behavior_event", "booking_behavior_projection", "user_preference_feature", "search_impression")) sql.update("DELETE FROM " + table);
        sql.update("DELETE FROM processed_event WHERE consumer_group=?", GROUP);
        sql.update("DELETE FROM event_projection_version WHERE consumer_group=?", GROUP);
    }

    @AfterAll
    static void close() { if (pool != null) pool.close(); }

    @Test
    void rejectsForgedUserCandidateAndBusinessFeedback() {
        var impression = impression();
        assertThrows(BookingFeedbackService.Rejected.class, () -> feedback.recordFeedback(8, new BookingFeedbackService.FeedbackRequest(impression.id(), "55", "CLICK")));
        assertThrows(BookingFeedbackService.Rejected.class, () -> feedback.recordFeedback(7, new BookingFeedbackService.FeedbackRequest(impression.id(), "56", "CLICK")));
        assertThrows(BookingFeedbackService.Rejected.class, () -> feedback.recordFeedback(7, new BookingFeedbackService.FeedbackRequest(impression.id(), "55", "CONFIRMED")));
        assertEquals(0, count("user_behavior_event"));
    }

    @Test
    void deduplicatesFeedbackAndClearedNegativeNeverReappearsOnRebuildOrReplay() {
        var impression = impression();
        var click = new BookingFeedbackService.FeedbackRequest(impression.id(), "55", "CLICK");
        assertFalse(feedback.recordFeedback(7, click).duplicate());
        assertTrue(feedback.recordFeedback(7, click).duplicate());
        var negative = new BookingFeedbackService.FeedbackRequest(impression.id(), "55", "NOT_INTERESTED");
        feedback.recordFeedback(7, negative);
        assertTrue(feedback.features(7, List.of("55")).get("55").negative());
        feedback.clearNegativeFeedback(7, "55");
        assertTrue(feedback.recordFeedback(7, negative).duplicate());
        sql.update("DELETE FROM user_preference_feature");
        var rebuilt = feedback.rebuild(7, "55");
        assertFalse(rebuilt.negative());
        assertEquals(1, rebuilt.clicks());
        assertEquals(0, rebuilt.confirmed());
    }

    @Test
    void lateConfirmedFactIsCountedWhileUsedProjectionStaysAtHigherVersion() throws Exception {
        var impression = impression();
        String booking = UUID.randomUUID().toString();
        byte[] used = event(UUID.randomUUID().toString(), booking, "used", 3, 7, impression.id(), impression.issuedAt().plusSeconds(3));
        byte[] confirmed = event(UUID.randomUUID().toString(), booking, "confirmed", 2, 7, impression.id(), impression.issuedAt().plusSeconds(2));
        assertEquals(InboxOutcome.PROCESSED, projector.process(used));
        assertEquals(InboxOutcome.STALE, projector.process(confirmed));
        assertEquals(InboxOutcome.DUPLICATE, projector.process(confirmed));
        assertEquals("USED", sql.queryForObject("SELECT current_status FROM booking_behavior_projection WHERE booking_id=?", String.class, booking));
        assertEquals(3L, sql.queryForObject("SELECT aggregate_version FROM booking_behavior_projection WHERE booking_id=?", Long.class, booking));
        var feature = feedback.features(7, List.of("55")).get("55");
        assertEquals(1, feature.confirmed()); assertEquals(1, feature.used());
        assertEquals(2, count("user_behavior_event"));
    }

    @Test
    void differentPayloadUnderSameIdIsQuarantinedWithoutChangingFacts() throws Exception {
        var impression = impression();
        String eventId = UUID.randomUUID().toString(), booking = UUID.randomUUID().toString();
        byte[] confirmed = event(eventId, booking, "confirmed", 2, 7, impression.id(), impression.issuedAt().plusSeconds(1));
        byte[] used = event(eventId, booking, "used", 3, 7, impression.id(), impression.issuedAt().plusSeconds(2));
        projector.process(confirmed);
        assertEquals(InboxOutcome.QUARANTINED, projector.process(used));
        String reason = sql.queryForObject("SELECT quarantine_reason FROM processed_event WHERE consumer_group=? AND event_id=?", String.class, GROUP, eventId);
        assertTrue(reason.contains("original=")); assertTrue(reason.contains("incoming="));
        assertEquals(1, count("user_behavior_event"));
        assertEquals("CONFIRMED", sql.queryForObject("SELECT current_status FROM booking_behavior_projection WHERE booking_id=?", String.class, booking));
    }

    @Test
    void factFailureRollsBackInboxAndVersionWatermarkThenRetryWorks() throws Exception {
        var impression = impression();
        String eventId = UUID.randomUUID().toString();
        byte[] body = event(eventId, UUID.randomUUID().toString(), "confirmed", 2, 7, impression.id(), impression.issuedAt().plusSeconds(1));
        sql.execute("CREATE TRIGGER booking_feedback_it_reject_fact BEFORE INSERT ON user_behavior_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='feedback transaction acceptance failure'");
        try { assertThrows(DataAccessException.class, () -> projector.process(body)); }
        finally { sql.execute("DROP TRIGGER booking_feedback_it_reject_fact"); }
        assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class, GROUP));
        assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM event_projection_version WHERE consumer_group=?", Integer.class, GROUP));
        assertEquals(0, count("booking_behavior_projection"));
        assertEquals(InboxOutcome.PROCESSED, projector.process(body));
        assertEquals(1, feedback.features(7, List.of("55")).get("55").confirmed());
    }

    @Test
    void recordsRealBusinessWithoutAttributingUnissuedOrOtherUsersImpression() throws Exception {
        var impression = impression();
        projector.process(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "used", 3, 8, impression.id(), impression.issuedAt().plusSeconds(1)));
        projector.process(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "confirmed", 2, 7, UUID.randomUUID().toString(), impression.issuedAt().plusSeconds(2)));
        projector.process(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "confirmed", 2, 9, "legacy-trace-".repeat(10), impression.issuedAt().plusSeconds(2)));
        assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM user_behavior_event WHERE attributed=TRUE", Integer.class));
        assertEquals(3, count("user_behavior_event"));
        assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM user_behavior_event WHERE user_id=9 AND impression_id IS NULL", Integer.class));
        assertEquals(1, feedback.features(8, List.of("55")).get("55").used());
    }

    @Test
    void expiredImpressionCannotAttributeNewBookingButVerifiedBookingKeepsFulfillmentAttribution() throws Exception {
        var impression = impression();
        sql.update("UPDATE search_impression SET expires_at=DATE_ADD(issued_at,INTERVAL 1 SECOND) WHERE id=?", impression.id());
        String verifiedBooking = UUID.randomUUID().toString();
        projector.process(event(UUID.randomUUID().toString(), verifiedBooking, "confirmed", 2, 7,
                impression.id(), impression.issuedAt().plusMillis(500)));
        String lateNewBooking = UUID.randomUUID().toString();
        projector.process(event(UUID.randomUUID().toString(), lateNewBooking, "confirmed", 2, 7,
                impression.id(), impression.issuedAt().plusSeconds(2)));
        assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM user_behavior_event WHERE booking_id=? AND attributed=TRUE", Integer.class, lateNewBooking));
        sql.update("DELETE FROM search_impression WHERE id=?", impression.id());
        projector.process(event(UUID.randomUUID().toString(), verifiedBooking, "used", 3, 7,
                impression.id(), impression.issuedAt().plusSeconds(3)));
        assertEquals(2, sql.queryForObject("SELECT COUNT(*) FROM user_behavior_event WHERE booking_id=? AND attributed=TRUE", Integer.class, verifiedBooking));
    }

    @Test
    void concurrentDistinctEventsDoNotLoseFeatureCounts() throws Exception {
        var impression = impression();
        List<Callable<InboxOutcome>> operations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            byte[] body = event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "confirmed", 2, 7, impression.id(), impression.issuedAt().plusSeconds(1));
            operations.add(() -> projector.process(body));
        }
        var workers = Executors.newFixedThreadPool(8);
        try {
            for (var future : workers.invokeAll(operations)) assertEquals(InboxOutcome.PROCESSED, future.get());
        } finally { workers.shutdownNow(); }
        assertEquals(8, feedback.features(7, List.of("55")).get("55").confirmed());
    }

    @Test
    void distinctUserShopPairsCanInsertInitialProjectionsConcurrently() throws Exception {
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        List<Callable<InboxOutcome>> operations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ObjectNode envelope = (ObjectNode) JSON.readTree(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "confirmed", 1, 71000 + i, null, Instant.now()));
            ((ObjectNode) envelope.get("payload")).put("shopId", String.valueOf(55000 + i));
            byte[] body = JSON.writeValueAsBytes(envelope);
            operations.add(() -> { ready.countDown(); start.await(); return projector.process(body); });
        }
        var workers = Executors.newFixedThreadPool(8);
        try {
            var futures = operations.stream().map(workers::submit).toList();
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) assertEquals(InboxOutcome.PROCESSED, future.get(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally { start.countDown(); workers.shutdownNow(); }
        assertEquals(8, count("booking_behavior_projection"));
        assertEquals(8, sql.queryForObject("SELECT SUM(confirmed) FROM user_preference_feature", Integer.class));
    }

    @Test
    void consumesActualZeroBasedOutboxEnvelopeAndRejectsMissingOrNonIntegerVersions() throws Exception {
        // Captured verbatim from the isolated Commerce normal-load test Outbox, 2026-09-10.
        byte[] realOutbox;
        try (var stream = BookingFeedbackIT.class.getResourceAsStream("/booking/commerce-held-outbox-v1.json")) {
            assertNotNull(stream);
            realOutbox = stream.readAllBytes();
        }
        assertEquals(InboxOutcome.PROCESSED, projector.process(realOutbox));
        assertEquals("HELD", sql.queryForObject("SELECT current_status FROM booking_behavior_projection", String.class));
        assertEquals(0, sql.queryForObject("SELECT aggregate_version FROM booking_behavior_projection", Integer.class));
        assertEquals(0, sql.queryForObject("SELECT schedule_version FROM booking_behavior_projection", Integer.class));
        for (String field : List.of("aggregateVersion", "scheduleVersion")) {
            for (String invalid : List.of("missing", "negative", "fraction", "string")) {
                ObjectNode envelope = (ObjectNode) JSON.readTree(realOutbox);
                envelope.put("eventId", UUID.randomUUID().toString());
                ObjectNode owner = field.equals("scheduleVersion") ? (ObjectNode) envelope.get("payload") : envelope;
                switch (invalid) {
                    case "missing" -> owner.remove(field);
                    case "negative" -> owner.put(field, -1);
                    case "fraction" -> owner.put(field, 0.5);
                    case "string" -> owner.put(field, "0");
                }
                byte[] body = JSON.writeValueAsBytes(envelope);
                assertThrows(IllegalArgumentException.class, () -> projector.process(body));
            }
        }
        assertEquals(1, count("user_behavior_event"));
        assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer_group=?", Integer.class, GROUP));
    }

    @Test
    void migrationRetainsRowsAndCleanupKeepsBusinessFactsOutsideFeatureWindow() throws Exception {
        var impression = impression();
        migrate(); migrate();
        assertEquals(1, count("search_impression"));
        projector.process(event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "used", 3, 7, null, Instant.now().minus(31, ChronoUnit.DAYS)));
        assertEquals(0, feedback.features(7, List.of("55")).get("55").used());
        sql.update("UPDATE search_impression SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY)");
        feedback.cleanupExpired();
        assertEquals(0, count("search_impression"));
        assertEquals(1, count("user_behavior_event"));
        assertEquals(1, count("booking_behavior_projection"));
    }

    private static BookingFeedbackService.Impression impression() { return feedback.issueImpression(7, List.of("55"), "booking-rules-test-v1"); }
    private static int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static byte[] event(String eventId, String bookingId, String action, long version, long userId, String impressionId, Instant occurredAt) throws Exception {
        String state = switch (action) { case "used" -> "USED"; case "confirmed", "rescheduled" -> "CONFIRMED"; default -> "HELD"; };
        var payload = new BookingChangedEvent(bookingId, "55", String.valueOf(userId), "101", "201", 2, 1,
                state, 0, occurredAt.plusSeconds(3600), occurredAt.plusSeconds(7200), Map.of("pricingMode", "ON_SITE"),
                "PLAN", impressionId, "request-it", userId);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId); envelope.put("eventType", "commerce.booking." + action);
        envelope.put("schemaVersion", 1); envelope.put("aggregateType", "commerce_booking");
        envelope.put("aggregateId", bookingId); envelope.put("aggregateVersion", version);
        envelope.put("occurredAt", occurredAt); envelope.put("sourceService", "commerce-service"); envelope.put("payload", payload);
        return JSON.writeValueAsBytes(envelope);
    }
}
