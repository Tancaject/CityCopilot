package com.citycopilot.commerce.booking;

import com.citycopilot.commerce.CommerceApiException;
import com.citycopilot.commerce.CommerceOrderCompletionMetrics;
import com.citycopilot.commerce.CommerceOutboxArchive;
import com.citycopilot.commerce.PlaceAuthorityClient;
import com.citycopilot.commerce.pricing.BookingPricingService;
import com.citycopilot.commerce.pricing.BookingQuoteCleanup;
import com.citycopilot.contract.booking.BookingContracts.*;
import com.citycopilot.contract.booking.PricingContracts.*;
import tools.jackson.databind.json.JsonMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Run only against an explicitly supplied disposable MySQL database ending in _test. */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_BOOKING_TEST_DB_URL", matches = "jdbc:mysql:.+")
class BookingLifecycleIT {
    private static HikariDataSource firstPool;
    private static HikariDataSource secondPool;
    private static JdbcTemplate sql;
    private static BookingService first;
    private static BookingService second;
    private static BookingPricingService pricing;
    private static BookingResourceService resources;
    private static PlaceAuthorityClient authority;
    private Instant tomorrow;

    @BeforeAll
    static void connect() throws Exception {
        firstPool = pool("booking-it-first");
        secondPool = pool("booking-it-second");
        try (var connection = firstPool.getConnection()) {
            if (!connection.getCatalog().endsWith("_test")) {
                throw new IllegalStateException("Booking acceptance requires a disposable database ending in _test");
            }
        }
        sql = new JdbcTemplate(firstPool);
        Path root = Path.of("../..").toAbsolutePath().normalize();
        var migration = org.flywaydb.core.Flyway.configure().dataSource(firstPool)
                .locations("filesystem:" + root.resolve("deploy/mysql/commerce"))
                .cleanDisabled(true).load();
        migration.migrate();
        assertEquals(0, migration.migrate().migrationsExecuted);
        authority = new PlaceAuthorityClient("http://127.0.0.1", "unused-test-token",
                java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(3), 1) {
            @Override
            public Map<String, Object> requireWriteAuthority(long shopId, long actorId, boolean admin) {
                if (actorId != 900 && !admin) throw new CommerceApiException("COMMERCE_SHOP_FORBIDDEN", "Test merchant does not own this shop", 403);
                return Map.of("exists", true, "ownerUserId", 900L, "shopId", shopId);
            }
        };
        first = service(firstPool, true);
        second = service(secondPool, true);
        pricing = pricing(firstPool);
        resources = new BookingResourceService(sql,
                new TransactionTemplate(new DataSourceTransactionManager(firstPool)),
                authority, new BookingPolicy("booking-test-only-signing-secret"));
    }

    private static HikariDataSource pool(String name) {
        var config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(System.getenv("CITYCOPILOT_BOOKING_TEST_DB_URL"));
        config.setUsername(System.getenv().getOrDefault("CITYCOPILOT_BOOKING_TEST_DB_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("CITYCOPILOT_BOOKING_TEST_DB_PASSWORD", ""));
        config.setMaximumPoolSize(12);
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        return new HikariDataSource(config);
    }

    private static BookingService service(HikariDataSource pool, boolean newBookingsEnabled) {
        var policy = new BookingPolicy(60, 14, 120, 1, 3, 8, 30, 15, 30,
                "Asia/Shanghai", "booking-test-only-signing-secret", newBookingsEnabled);
        return new BookingService(new JdbcTemplate(pool),
                new TransactionTemplate(new DataSourceTransactionManager(pool)),
                JsonMapper.builderWithJackson2Defaults().findAndAddModules().build(), authority, policy, pricing(pool));
    }

    private static BookingPricingService pricing(HikariDataSource pool) {
        return new BookingPricingService(new JdbcTemplate(pool),
                new TransactionTemplate(new DataSourceTransactionManager(pool)),
                JsonMapper.builderWithJackson2Defaults().findAndAddModules().build(), authority);
    }

    @BeforeEach
    void resetFixture() {
        sql.execute("DROP TRIGGER IF EXISTS booking_it_reject_outbox");
        sql.execute("DROP TRIGGER IF EXISTS outbox_it_reject_archive");
        sql.update("DELETE FROM commerce_outbox_payload_archive");
        for (String table : List.of("commerce_booking_quote", "commerce_user_booking_coupon", "commerce_booking_coupon_rule", "commerce_booking_offer",
                "commerce_booking_reminder", "commerce_booking_command", "commerce_booking",
                "commerce_booking_user_guard", "commerce_booking_slot", "commerce_booking_resource", "commerce_outbox_event")) {
            sql.update("DELETE FROM " + table);
        }
        tomorrow = sql.queryForObject("SELECT UTC_TIMESTAMP(3)", java.sql.Timestamp.class)
                .toInstant().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    }

    @AfterAll
    static void close() {
        if (secondPool != null) secondPool.close();
        if (firstPool != null) firstPool.close();
    }

    @Test
    void outboxArchivalIsAtomicBoundedAndPreservesOriginalIdentityAndBytes() throws Exception {
        String body = "{\"eventId\":\"archive-old-1\", \"detail\":\"原始正文\\n与格式\"}";
        for (String id : List.of("archive-old-1", "archive-old-2", "archive-ready", "archive-recent")) {
            sql.update("""
                    INSERT INTO commerce_outbox_event(event_id,event_type,aggregate_type,aggregate_id,
                        aggregate_version,topic,payload_json,status,published_at)
                    VALUES (?,'commerce.order.created','commerce_order',?,0,'citycopilot-commerce-events-v1',?,
                        'PUBLISHED',CURRENT_TIMESTAMP(3)-INTERVAL 3 DAY)
                    """, id, id, body);
        }
        sql.update("UPDATE commerce_outbox_event SET status='READY',published_at=NULL WHERE event_id='archive-ready'");
        sql.update("UPDATE commerce_outbox_event SET published_at=CURRENT_TIMESTAMP(3) WHERE event_id='archive-recent'");
        var archive = new CommerceOutboxArchive(sql,
                new TransactionTemplate(new DataSourceTransactionManager(firstPool)), 1, 1);
        try (var locked = secondPool.getConnection()) {
            locked.setAutoCommit(false);
            try (var statement = locked.prepareStatement("SELECT event_id FROM commerce_outbox_event WHERE event_id='archive-old-1' FOR UPDATE")) {
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            var worker = Executors.newSingleThreadExecutor();
            try {
                assertEquals(1, worker.submit(archive::archive).get(10, TimeUnit.SECONDS).archived());
                assertEquals(body, sql.queryForObject("SELECT payload_json FROM commerce_outbox_event WHERE event_id='archive-old-1'", String.class));
                assertNull(sql.queryForObject("SELECT payload_json FROM commerce_outbox_event WHERE event_id='archive-old-2'", String.class));
            } finally { worker.shutdownNow(); }
            locked.rollback();
        }
        sql.execute("""
                CREATE TRIGGER outbox_it_reject_archive BEFORE UPDATE ON commerce_outbox_event
                FOR EACH ROW BEGIN
                    IF NEW.payload_json IS NULL AND OLD.event_id='archive-old-1' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='archive transaction rejected';
                    END IF;
                END
                """);
        try {
            assertThrows(DataAccessException.class, archive::archive);
            assertEquals(body, sql.queryForObject("SELECT payload_json FROM commerce_outbox_event WHERE event_id='archive-old-1'", String.class));
            assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM commerce_outbox_payload_archive WHERE event_id='archive-old-1'", Integer.class));
        } finally { sql.execute("DROP TRIGGER outbox_it_reject_archive"); }
        assertEquals(1, archive.archive().archived());
        assertEquals(0, archive.archive().archived());
        assertEquals(4, count("commerce_outbox_event"));
        assertEquals(2, count("commerce_outbox_payload_archive"));
        assertEquals(body, sql.queryForObject("SELECT payload_json FROM commerce_outbox_payload_archive WHERE event_id='archive-old-1'", String.class));
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                sql.queryForObject("SELECT payload_sha256 FROM commerce_outbox_payload_archive WHERE event_id='archive-old-1'", String.class));
        // 实际读取路径也使用归档原文；只构造消息，不连接 Broker 或发送。
        sql.update("UPDATE commerce_outbox_event SET status='READY' WHERE event_id='archive-old-1'");
        var registry = new SimpleMeterRegistry();
        try {
            var relay = new com.citycopilot.commerce.CommerceOutboxRelay(sql, registry, null, false);
            Object claim = org.springframework.test.util.ReflectionTestUtils.invokeMethod(relay, "claim", "archive-old-1");
            org.apache.kafka.clients.producer.ProducerRecord<String, String> record =
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(relay, "claimedRecord", claim);
            assertNotNull(record);
            assertEquals(body, record.value());
            assertEquals("archive-old-1", new String(record.headers().lastHeader("eventId").value(),
                    java.nio.charset.StandardCharsets.UTF_8));
        } finally { registry.close(); }
        assertThrows(DataAccessException.class, () -> sql.update("""
                INSERT INTO commerce_outbox_event(event_id,event_type,aggregate_type,aggregate_id,
                    aggregate_version,topic,payload_json,status)
                VALUES ('duplicate-id','commerce.order.created','commerce_order','archive-old-1',0,'topic','{}','READY')
                """));
        assertThrows(DataAccessException.class, () -> sql.update(
                "UPDATE commerce_outbox_event SET payload_json=NULL WHERE event_id='archive-ready'"));
        assertEquals(body, sql.queryForObject("SELECT payload_json FROM commerce_outbox_event WHERE event_id='archive-ready'", String.class));
        assertEquals(body, sql.queryForObject("SELECT payload_json FROM commerce_outbox_event WHERE event_id='archive-recent'", String.class));
    }

    @Test
    void orderCompletionSamplingUsesCommittedIdentitiesAcrossRestartsAndKeepsOldBacklog() throws Exception {
        sql.update("""
                INSERT INTO commerce_admission_ledger(
                    order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
                    routing_generation,physical_shard_count,status,accepted_at,terminal_at)
                VALUES
                    (8601,8601,'metrics-1',1,'seckill:v1:1','v1',1,'ACCEPTED',CURRENT_TIMESTAMP(3)-INTERVAL 10 MINUTE,NULL),
                    (8602,8602,'metrics-2',1,'seckill:v1:1','v1',1,'ORDER_CREATED',CURRENT_TIMESTAMP(3)-INTERVAL 600 SECOND,CURRENT_TIMESTAMP(3)-INTERVAL 590 SECOND),
                    (8603,8603,'metrics-3',1,'seckill:v1:1','v1',1,'ACCEPTED',CURRENT_TIMESTAMP(3)-INTERVAL 1 HOUR,NULL),
                    (8604,8604,'metrics-4',1,'seckill:v1:1','v1',1,'REPAIR_REQUIRED',CURRENT_TIMESTAMP(3)-INTERVAL 5 MINUTE,CURRENT_TIMESTAMP(3)),
                    (8605,8605,'metrics-5',1,'seckill:v1:1','legacy-order-v0',1,'ORDER_CREATED',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
                    (8606,8606,'metrics-6',1,'seckill:v1:1','v1',1,'ORDER_CREATED',CURRENT_TIMESTAMP(3)-INTERVAL 1 HOUR,CURRENT_TIMESTAMP(3)-INTERVAL 50 MINUTE)
                """);
        sql.update("""
                INSERT INTO commerce_order(id,user_id,voucher_id,shop_id,shop_name_snapshot,
                    voucher_title_snapshot,pay_value_snapshot,actual_value_snapshot,status,idempotency_key,create_time)
                SELECT order_id,user_id,voucher_id,1,'Metrics shop','Metrics voucher',100,100,1,idempotency_key,terminal_at
                FROM commerce_admission_ledger WHERE order_id IN (8602,8605,8606)
                """);
        var registry = new SimpleMeterRegistry();
        var restartedRegistry = new SimpleMeterRegistry();
        try {
            var metrics = new CommerceOrderCompletionMetrics(sql, registry);
            metrics.refresh();
            assertEquals(2, registry.get("citycopilot.commerce.order.pending").gauge().value());
            assertTrue(registry.get("citycopilot.commerce.order.pending.oldest.age").gauge().value() >= 3600);
            assertEquals(1, registry.get("citycopilot.commerce.order.repair.required").gauge().value());
            assertEquals(3, registry.get("citycopilot.commerce.order.completion.recent.admitted").gauge().value());
            assertEquals(1, registry.get("citycopilot.commerce.order.completion.recent.persisted").gauge().value());
            assertEquals(10, registry.get("citycopilot.commerce.order.completion.recent.mean.duration").gauge().value());

            try (var uncommitted = secondPool.getConnection()) {
                uncommitted.setAutoCommit(false);
                try (var statement = uncommitted.createStatement()) {
                    statement.executeUpdate("UPDATE commerce_admission_ledger SET status='ORDER_CREATED',terminal_at=CURRENT_TIMESTAMP(3) WHERE order_id=8601");
                    statement.executeUpdate("""
                            INSERT INTO commerce_order(id,user_id,voucher_id,shop_id,shop_name_snapshot,
                                voucher_title_snapshot,pay_value_snapshot,actual_value_snapshot,status,idempotency_key)
                            VALUES (8601,8601,1,1,'Metrics shop','Metrics voucher',100,100,1,'metrics-1')
                            """);
                }
                metrics.refresh();
                assertEquals(1, registry.get("citycopilot.commerce.order.completion.recent.persisted").gauge().value());
                uncommitted.rollback();
            }
            new TransactionTemplate(new DataSourceTransactionManager(firstPool)).executeWithoutResult(transaction -> {
                sql.update("UPDATE commerce_admission_ledger SET status='ORDER_CREATED',terminal_at=accepted_at+INTERVAL 20 SECOND WHERE order_id=8601");
                sql.update("""
                        INSERT INTO commerce_order(id,user_id,voucher_id,shop_id,shop_name_snapshot,
                            voucher_title_snapshot,pay_value_snapshot,actual_value_snapshot,status,idempotency_key,create_time)
                        SELECT order_id,user_id,voucher_id,1,'Metrics shop','Metrics voucher',100,100,1,idempotency_key,terminal_at
                        FROM commerce_admission_ledger WHERE order_id=8601
                        """);
            });
            metrics.refresh();
            metrics.refresh();
            assertEquals(2, registry.get("citycopilot.commerce.order.completion.recent.persisted").gauge().value());
            assertEquals(15, registry.get("citycopilot.commerce.order.completion.recent.mean.duration").gauge().value());
            assertEquals(20, registry.get("citycopilot.commerce.order.completion.recent.max.duration").gauge().value());
            var restarted = new CommerceOrderCompletionMetrics(new JdbcTemplate(secondPool), restartedRegistry);
            restarted.refresh();
            assertEquals(2, restartedRegistry.get("citycopilot.commerce.order.completion.recent.persisted").gauge().value());
            assertEquals(1, restartedRegistry.get("citycopilot.commerce.order.pending").gauge().value());
            assertEquals("status,accepted_at", sql.queryForObject("""
                    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_admission_ledger'
                      AND INDEX_NAME='idx_commerce_admission_status_age'
                    """, String.class));
            assertEquals("accepted_at,status,routing_generation", sql.queryForObject("""
                    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_admission_ledger'
                      AND INDEX_NAME='idx_commerce_admission_completion_window'
                    """, String.class));
            sql.update("DELETE FROM commerce_admission_ledger WHERE order_id BETWEEN 8601 AND 8606");
            metrics.refresh();
            assertEquals(0, registry.get("citycopilot.commerce.order.pending").gauge().value());
            assertEquals(0, registry.get("citycopilot.commerce.order.completion.recent.admitted").gauge().value());
            assertEquals(0, registry.get("citycopilot.commerce.order.completion.recent.persisted").gauge().value());
            assertTrue(Double.isNaN(registry.get("citycopilot.commerce.order.completion.recent.mean.duration").gauge().value()));
        } finally {
            registry.close();
            restartedRegistry.close();
            sql.update("DELETE FROM commerce_order WHERE id BETWEEN 8601 AND 8606");
            sql.update("DELETE FROM commerce_admission_ledger WHERE order_id BETWEEN 8601 AND 8606");
        }
    }

    @Test
    void twoInstancesRespectCourtAndDiningCapacityAndUserQuota() throws Exception {
        var court = resource("COURT", 10L, 4);
        var courtSlot = slot(court, tomorrow, 1);
        var courtResults = race(16, i -> hold(i % 2 == 0 ? first : second, 100 + i, "court-" + i, court, courtSlot, 2));
        assertEquals(1, courtResults.stream().filter(this::success).count());
        assertEquals(1, occupied(courtSlot));

        var dining = resource("DINING_POOL", 20L, 8);
        var dinner = slot(dining, tomorrow, 10);
        var dinnerResults = race(8, i -> hold(i % 2 == 0 ? first : second, 200 + i, "dinner-" + i, dining, dinner, 4));
        assertEquals(2, dinnerResults.stream().filter(this::success).count());
        assertEquals(8, occupied(dinner));

        var later = slot(dining, tomorrow.plus(2, ChronoUnit.HOURS), 40);
        var quotaResults = race(8, i -> hold(i % 2 == 0 ? first : second, 300, "quota-" + i, dining, later, 2));
        assertEquals(1, quotaResults.stream().filter(this::success).count());
        assertEquals(2, occupied(later));
        assertCapacityInvariant();
    }

    @Test
    void committedCommandsReplayBeforeMutableVersionAndCutoffChecks() throws Exception {
        var resource = resource("COURT", 10L, 4);
        var slot = slot(resource, tomorrow, 1);
        var request = holdRequest(resource, slot, 2);
        var results = race(10, i -> (i % 2 == 0 ? first : second).hold(1L, "same-key", request));
        assertTrue(results.stream().allMatch(this::success));
        assertEquals(1L, results.stream().map(r -> r.booking().id()).distinct().count());
        var held = results.get(0).booking();
        var confirmRequest = new ActionRequest(held.version(), null, null);
        var confirmed = first.confirm(1, held.id(), "confirm-key", confirmRequest);
        var replay = second.confirm(1, held.id(), "confirm-key", confirmRequest);
        assertEquals(confirmed, replay);
        assertEquals("CONFIRMED", second.booking(held.id(), 1).status());
        assertThrows(CommerceApiException.class,
                () -> second.hold(1, "same-key", holdRequest(resource, slot, 3)));
        assertEquals(1, count("commerce_booking"));
        assertEquals(2, count("commerce_booking_command"));
        assertCapacityInvariant();
    }

    @Test
    void rescheduleKeepsOriginalOnFullTargetAndAppliesSameSlotPartyDelta() {
        var resource = resource("DINING_POOL", 20L, 8);
        var oldSlot = slot(resource, tomorrow, 8);
        var fullSlot = slot(resource, tomorrow.plus(2, ChronoUnit.HOURS), 4);
        var original = confirm(1, resource, oldSlot, 3);
        confirm(2, resource, fullSlot, 4);
        reject(() -> first.reschedule(1, original.id(), "full-target",
                new RescheduleRequest(original.version(), resource.id(), fullSlot.id(), 3, null)));
        var preserved = second.booking(original.id(), 1);
        assertEquals(original.slotId(), preserved.slotId());
        assertEquals(original.version(), preserved.version());
        assertEquals(original.priceSnapshot(), preserved.priceSnapshot());
        assertEquals(original.checkinToken(), preserved.checkinToken());

        var more = first.reschedule(1, original.id(), "more-people",
                new RescheduleRequest(original.version(), resource.id(), oldSlot.id(), 5, null)).booking();
        assertEquals(5, occupied(oldSlot));
        assertEquals(5, more.units());
        assertEquals(original.scheduleVersion() + 1, more.scheduleVersion());
        assertNotEquals(original.checkinToken(), more.checkinToken());
        reject(() -> first.reschedule(1, more.id(), "stale-version",
                new RescheduleRequest(original.version(), resource.id(), oldSlot.id(), 2, null)));
        var fewer = second.reschedule(1, more.id(), "fewer-people",
                new RescheduleRequest(more.version(), resource.id(), oldSlot.id(), 2, null)).booking();
        assertEquals(2, fewer.units());
        assertEquals(2, occupied(oldSlot));
        assertCapacityInvariant();
    }

    @Test
    void outboxFailureRollsBackNewAndOldSlotsBookingAndCommandTogether() {
        var resource = resource("COURT", 10L, 4);
        var oldSlot = slot(resource, tomorrow, 1);
        var target = slot(resource, tomorrow.plus(2, ChronoUnit.HOURS), 1);
        var original = confirm(1, resource, oldSlot, 2);
        sql.execute("CREATE TRIGGER booking_it_reject_outbox BEFORE INSERT ON commerce_outbox_event "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'booking acceptance injected outbox failure'");
        try {
            assertThrows(DataAccessException.class, () -> first.reschedule(1, original.id(), "rollback-move",
                    new RescheduleRequest(original.version(), resource.id(), target.id(), 2, null)));
            assertEquals(1, occupied(oldSlot));
            assertEquals(0, occupied(target));
            assertEquals(original.version(), second.booking(original.id(), 1).version());
            assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM commerce_booking_command WHERE idempotency_key='rollback-move'", Integer.class));
        } finally {
            sql.execute("DROP TRIGGER booking_it_reject_outbox");
        }
        var moved = first.reschedule(1, original.id(), "rollback-move",
                new RescheduleRequest(original.version(), resource.id(), target.id(), 2, null));
        assertTrue(success(moved));
        assertEquals(0, occupied(oldSlot));
        assertEquals(1, occupied(target));
        assertCapacityInvariant();
    }

    @Test
    void expiryRacesConfirmationAndUserCancellationReleasesOnlyOnce() throws Exception {
        var resource = resource("DINING_POOL", 20L, 8);
        var slot = slot(resource, tomorrow, 8);
        var held = first.hold(1, "expiring", holdRequest(resource, slot, 4)).booking();
        sql.update("UPDATE commerce_booking SET hold_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", held.id());
        race(2, i -> {
            if (i == 0) return first.confirm(1, held.id(), "late-confirm", new ActionRequest(held.version(), null, null));
            second.expireDue(Runnable::run);
            return null;
        });
        first.expireDue(Runnable::run);
        assertEquals("HOLD_EXPIRED", first.booking(held.id(), 1).status());
        assertEquals(0, occupied(slot));

        var cancelled = first.hold(2, "cancel-hold", holdRequest(resource, slot, 3)).booking();
        var action = new ActionRequest(cancelled.version(), null, null);
        var result = second.cancel(2, false, false, cancelled.id(), "cancel-key", action);
        assertEquals(result, first.cancel(2, false, false, cancelled.id(), "cancel-key", action));
        assertEquals("CANCELLED", result.booking().status());
        assertEquals(0, occupied(slot));
        assertCapacityInvariant();
    }

    @Test
    void checkinUsesCurrentScheduleTokenAndDoesNotClaimPayment() {
        var resource = resource("DINING_POOL", 20L, 8);
        var slot = slot(resource, tomorrow, 8);
        var original = confirm(1, resource, slot, 3);
        var checkinSlot = slot(resource, sql.queryForObject("SELECT UTC_TIMESTAMP(3)", java.sql.Timestamp.class)
                .toInstant().plus(5, ChronoUnit.MINUTES), 8);
        var changed = first.reschedule(1, original.id(), "change-party",
                new RescheduleRequest(original.version(), resource.id(), checkinSlot.id(), 4, null)).booking();
        reject(() -> first.checkIn(900, false, changed.id(), "old-code",
                new ActionRequest(changed.version(), null, original.checkinToken())));
        var action = new ActionRequest(changed.version(), null, changed.checkinToken());
        var used = second.checkIn(900, false, changed.id(), "checkin", action);
        assertEquals("USED", used.booking().status());
        assertEquals(used, first.checkIn(900, false, changed.id(), "checkin", action));
        assertEquals("ON_SITE", used.booking().priceSnapshot().get("pricingMode"));
        assertNull(used.booking().priceSnapshot().get("totalCents"));
        assertEquals(0, occupied(slot));
        assertEquals(4, occupied(checkinSlot));
        assertCapacityInvariant();
    }

    @Test
    void stoppedNewEntryStillAllowsExistingConfirmationCancellationAndExpiry() {
        var resource = resource("COURT", 10L, 4);
        var slot = slot(resource, tomorrow, 1);
        var held = first.hold(1, "before-disable", holdRequest(resource, slot, 2)).booking();
        var closed = service(secondPool, false);
        var confirmed = closed.confirm(1, held.id(), "confirm-disabled", new ActionRequest(held.version(), null, null)).booking();
        assertEquals("CONFIRMED", closed.booking(held.id(), 1).status());
        var cancelled = closed.cancel(1, false, false, held.id(), "cancel-disabled", new ActionRequest(confirmed.version(), null, null));
        assertEquals("CANCELLED", cancelled.booking().status());
        reject(() -> closed.hold(2, "new-disabled", holdRequest(resource, slot, 2)));
        assertEquals(0, occupied(slot));
        assertCapacityInvariant();
    }

    @Test
    void resourceManagementPreservesAuthorityVersionAndOccupiedSlotRules() {
        var resource = resource("DINING_POOL", 20L, 8);
        var published = slot(resource, tomorrow, 8);
        var held = first.hold(1, "resource-boundary", holdRequest(resource, published, 3)).booking();
        long resourceId = Long.parseLong(resource.id());
        var rename = new ResourceRequest(resource.kind(), "Updated dining pool", resource.timezone(), 8, "OPEN", resource.version());
        assertEquals("COMMERCE_SHOP_FORBIDDEN", assertThrows(CommerceApiException.class,
                () -> resources.updateResource(resourceId, 901, false, rename)).code());
        var updated = resources.updateResource(resourceId, 900, false, rename);
        assertEquals(resource.version() + 1, updated.version());
        assertEquals("BOOKING_VERSION_CONFLICT", assertThrows(CommerceApiException.class,
                () -> resources.updateResource(resourceId, 900, false, rename)).code());
        assertEquals(updated, resources.resource(resourceId));
        assertEquals(List.of(updated), resources.merchantResources(20, 900, false));

        var occupied = resources.slot(Long.parseLong(published.id()));
        assertEquals(3, occupied.occupied());
        assertEquals("BOOKING_SLOT_OCCUPIED", assertThrows(CommerceApiException.class,
                () -> resources.saveSlot(resourceId, 900, false, new SlotRequest(occupied.id(),
                        occupied.startAt(), occupied.endAt(), 2, "OPEN", occupied.version()))).code());
        assertEquals("BOOKING_SLOT_OCCUPIED", assertThrows(CommerceApiException.class,
                () -> resources.saveSlot(resourceId, 900, false, new SlotRequest(occupied.id(),
                        occupied.startAt().plus(2, ChronoUnit.HOURS), occupied.endAt().plus(2, ChronoUnit.HOURS),
                        8, "OPEN", occupied.version()))).code());
        var resized = resources.saveSlot(resourceId, 900, false, new SlotRequest(occupied.id(),
                occupied.startAt(), occupied.endAt(), 5, "OPEN", occupied.version()));
        assertEquals(2, resized.available());
        assertEquals(List.of(resized), resources.slots(resourceId, tomorrow, tomorrow.plus(1, ChronoUnit.DAYS)));
        assertEquals("HELD", first.booking(held.id(), 1).status());
        assertEquals(1, count("commerce_booking_command"));
        assertEquals(1, count("commerce_outbox_event"));
        assertCapacityInvariant();
    }

    @Test
    void slotPublicationRejectsOverlapAndQuotaCountsConfirmedBookings() {
        var dining = resource("DINING_POOL", 20L, 8);
        slot(dining, tomorrow, 20);
        assertThrows(CommerceApiException.class, () -> resources.saveSlot(Long.parseLong(dining.id()), 900, false,
                new SlotRequest(null, tomorrow.plus(30, ChronoUnit.MINUTES), tomorrow.plus(90, ChronoUnit.MINUTES), 20, "OPEN", null)));
        slot(dining, tomorrow.plus(1, ChronoUnit.HOURS), 20);
        var court = resource("COURT", 10L, 4);
        for (int i = 0; i < 3; i++) confirm(1, court, slot(court, tomorrow.plus(i, ChronoUnit.HOURS), 1), 2);
        var fourth = slot(court, tomorrow.plus(3, ChronoUnit.HOURS), 1);
        reject(() -> first.hold(1, "fourth", holdRequest(court, fourth, 2)));
        assertEquals(0, occupied(fourth));
        assertCapacityInvariant();
    }

    @Test
    void unavailableHoldCommitsCancellationButStopNewHonorsExistingHold() {
        var resource = resource("COURT", 10L, 4);
        var slot = slot(resource, tomorrow, 1);
        var held = first.hold(1, "unavailable-hold", holdRequest(resource, slot, 2)).booking();
        var unavailable = resources.updateResource(Long.parseLong(resource.id()), 900, false,
                new ResourceRequest(resource.kind(), resource.name(), resource.timezone(), 4, "UNAVAILABLE", resource.version()));
        var action = new ActionRequest(held.version(), null, null);
        var cancelled = first.confirm(1, held.id(), "unavailable-confirm", action);
        assertEquals(409, cancelled.httpStatus());
        assertEquals("CANCELLED", cancelled.booking().status());
        assertEquals(cancelled, second.confirm(1, held.id(), "unavailable-confirm", action));
        assertEquals(0, occupied(slot));
        var open = resources.updateResource(Long.parseLong(resource.id()), 900, false,
                new ResourceRequest(resource.kind(), resource.name(), resource.timezone(), 4, "OPEN", unavailable.version()));
        var promised = first.hold(2, "promised-hold", holdRequest(resource, slot, 2)).booking();
        resources.updateResource(Long.parseLong(resource.id()), 900, false,
                new ResourceRequest(resource.kind(), resource.name(), resource.timezone(), 4, "STOP_NEW", open.version()));
        assertEquals("CONFIRMED", second.confirm(2, promised.id(), "promised-confirm",
                new ActionRequest(promised.version(), null, null)).booking().status());
        assertCapacityInvariant();
    }

    @Test
    void reminderVersionInvalidationAndNoShowKeepHistoricalCapacity() {
        var resource = resource("DINING_POOL", 20L, 8);
        var slot = slot(resource, tomorrow, 10);
        var booking = confirm(1, resource, slot, 4);
        // Simulate a persisted timer becoming due, then restart processing using the second service instance.
        sql.update("UPDATE commerce_booking_reminder SET due_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE booking_id=?", booking.id());
        second.deliverDueReminders(Runnable::run);
        var notice = first.notices(1).get(0);
        first.readNotice(1, Long.parseLong(notice.id()));
        assertNotNull(first.notices(1).get(0).readAt());
        var target = slot(resource, tomorrow.plus(2, ChronoUnit.HOURS), 10);
        var moved = second.reschedule(1, booking.id(), "reminder-move",
                new RescheduleRequest(booking.version(), resource.id(), target.id(), 4, null)).booking();
        assertTrue(first.notices(1).isEmpty());
        assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM commerce_booking_reminder WHERE booking_id=? AND status='PENDING' AND schedule_version=?",
                Integer.class, booking.id(), moved.scheduleVersion()));
        sql.update("UPDATE commerce_booking SET start_at=UTC_TIMESTAMP(3)-INTERVAL 2 HOUR,end_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE id=?", booking.id());
        first.expireDue(Runnable::run);
        second.expireDue(Runnable::run);
        assertEquals("NO_SHOW", first.booking(booking.id(), 1).status());
        assertEquals(4, occupied(target));
        assertTrue(first.notices(1).isEmpty());
        assertCapacityInvariant();
    }

    @Test
    void heldCanCancelNearStartAndConfirmedCancellationHonorsCutoff() {
        var resource = resource("DINING_POOL", 20L, 8);
        var start = sql.queryForObject("SELECT UTC_TIMESTAMP(3)", java.sql.Timestamp.class)
                .toInstant().plus(5, ChronoUnit.MINUTES);
        var slot = slot(resource, start, 8);
        var held = first.hold(1, "near-start", holdRequest(resource, slot, 2)).booking();
        assertEquals("CANCELLED", second.cancel(1, false, false, held.id(), "cancel-near-start",
                new ActionRequest(held.version(), null, null)).booking().status());
        var confirmed = confirm(2, resource, slot, 3);
        reject(() -> first.cancel(2, false, false, confirmed.id(), "late-cancel", new ActionRequest(confirmed.version(), null, null)));
        assertEquals("CONFIRMED", second.booking(confirmed.id(), 2).status());
        assertEquals(3, occupied(slot));
        assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM commerce_booking_reminder WHERE booking_id=? AND kind='REMINDER'", Integer.class, confirmed.id()));
        assertCapacityInvariant();
    }

    private ResourceView resource(String kind, long shopId, int maxPartySize) {
        return resources.createResource(shopId, 900, false,
                new ResourceRequest(kind, kind.equals("COURT") ? "羽毛球1号场" : "餐厅接待名额", "Asia/Shanghai", maxPartySize, "OPEN", null));
    }

    @Test
    void expiredQuoteCleanupIsBoundedSkipsLocksAndPreservesConsumedReplay() throws Exception {
        var resource = resource("COURT", 10L, 4);
        var slot = slot(resource, tomorrow, 1);
        var offer = offer(resource, 20_000);
        var accepted = quote(1, resource, slot, offer, 2);
        var request = pricedHold(resource, slot, 2, accepted);
        var expired = new ArrayList<QuoteView>();
        for (int i = 0; i < 3; i++) expired.add(quote(1, resource, slot, offer, 2));
        var recent = quote(1, resource, slot, offer, 2);
        var fresh = quote(1, resource, slot, offer, 2);
        var held = first.hold(1, "retained-hold", request).booking();
        for (var old : expired) sql.update(
                "UPDATE commerce_booking_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 2 DAY WHERE id=?", old.quoteId());
        sql.update("UPDATE commerce_booking_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 3 DAY WHERE id=?", accepted.quoteId());
        sql.update("UPDATE commerce_booking_quote SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 HOUR WHERE id=?", recent.quoteId());
        long outboxBefore = count("commerce_outbox_event");
        var cleanup = new BookingQuoteCleanup(sql,
                new TransactionTemplate(new DataSourceTransactionManager(firstPool)), 1, 1);
        try (var heldLock = secondPool.getConnection()) {
            heldLock.setAutoCommit(false);
            try (var lock = heldLock.prepareStatement("SELECT id FROM commerce_booking_quote WHERE id=? FOR UPDATE")) {
                lock.setString(1, expired.get(0).quoteId());
                try (var row = lock.executeQuery()) { assertTrue(row.next()); }
            }
            var worker = Executors.newSingleThreadExecutor();
            try {
                // 未用超时来判断正确性：返回后还须精确证明被锁行保留且批量上限生效。
                var result = worker.submit(cleanup::cleanup).get(10, TimeUnit.SECONDS);
                assertEquals(1, result.deleted());
                assertEquals(1, result.batchSize());
                assertEquals(5, count("commerce_booking_quote"));
                assertEquals(expired.get(0).quoteId(), pricing.getQuote(expired.get(0).quoteId(), 1).quoteId());
            } finally { worker.shutdownNow(); }
            heldLock.rollback();
        }
        assertEquals(1, cleanup.cleanup().deleted());
        assertEquals(1, cleanup.cleanup().deleted());
        assertEquals(0, cleanup.cleanup().deleted());
        assertEquals(3, count("commerce_booking_quote"));
        assertEquals(accepted.quoteId(), pricing.getQuote(accepted.quoteId(), 1).quoteId());
        assertEquals(recent.quoteId(), pricing.getQuote(recent.quoteId(), 1).quoteId());
        assertEquals(fresh.quoteId(), pricing.getQuote(fresh.quoteId(), 1).quoteId());
        CommerceApiException gone = assertThrows(CommerceApiException.class,
                () -> pricing.getQuote(expired.get(0).quoteId(), 1));
        assertEquals(404, gone.status());
        assertTrue(gone.getMessage().contains("重新报价"));
        assertEquals(held.id(), first.hold(1, "retained-hold", request).booking().id());
        assertEquals(1, occupied(slot));
        assertEquals(outboxBefore, count("commerce_outbox_event"));
        assertEquals("consumed_booking_id,expires_at,id", sql.queryForObject("""
                SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='commerce_booking_quote' AND INDEX_NAME='idx_booking_quote_cleanup'
                """, String.class));
    }

    @Test
    void quoteAndCouponAreSingleUseAndHoldFreezesTheAcceptedPrice() {
        var resource = resource("COURT", 10L, 4);
        var slot = slot(resource, tomorrow, 1);
        var later = slot(resource, tomorrow.plus(2, ChronoUnit.HOURS), 1);
        var offer = offer(resource, 20_000);
        claim(1, resource, "discount", "DISCOUNT", 0, 0, 9000);
        var reduction = claim(1, resource, "threshold", "FULL_REDUCTION", 20_000, 3000, 10_000);
        var cash = claim(1, resource, "cash", "CASH", 0, 4000, 10_000);
        var stale = quote(1, resource, slot, offer, 2);
        assertEquals(13_000L, stale.totalCents());
        assertEquals(2, stale.discountLines().size());
        var raised = pricing.saveOffer(resource.id(), new OfferRequest(offer.id(), offer.kind(), offer.title(), "场地使用", "器材租赁另付",
                21_000, "OPEN", offer.version()), 900, false);
        reject(() -> first.hold(1, "stale-price", pricedHold(resource, slot, 2, stale)));
        assertEquals(0, occupied(slot));
        assertEquals("AVAILABLE", couponStatus(cash));
        var accepted = quote(1, resource, slot, raised, 2);
        var otherQuoteUsingSameCoupons = quote(1, resource, later, raised, 2);
        var held = first.hold(1, "priced-hold", pricedHold(resource, slot, 2, accepted)).booking();
        assertEquals("RESERVED", couponStatus(reduction));
        assertEquals("RESERVED", couponStatus(cash));
        pricing.saveOffer(resource.id(), new OfferRequest(offer.id(), offer.kind(), offer.title(), "场地使用", "器材租赁另付",
                22_000, "OPEN", raised.version()), 900, false);
        var confirmed = second.confirm(1, held.id(), "priced-confirm", new ActionRequest(held.version(), null, null)).booking();
        assertEquals(14_000, ((Number) confirmed.priceSnapshot().get("totalCents")).intValue());
        reject(() -> first.hold(1, "duplicate-quote", pricedHold(resource, slot, 2, accepted)));
        reject(() -> first.hold(1, "duplicate-coupon", pricedHold(resource, later, 2, otherQuoteUsingSameCoupons)));
        assertEquals(0, occupied(later));
        first.cancel(1, false, false, held.id(), "priced-cancel", new ActionRequest(confirmed.version(), null, null));
        assertEquals("AVAILABLE", couponStatus(cash));
        assertEquals("AVAILABLE", couponStatus(reduction));
        assertCapacityInvariant();
    }

    @Test
    void couponRevocationBeforeConfirmationCommitsARecoverableCancellation() {
        var resource = resource("DINING_POOL", 20L, 8);
        var slot = slot(resource, tomorrow, 10);
        var offer = offer(resource, 10_000);
        var coupon = claim(1, resource, "revoke", "CASH", 0, 1000, 10_000);
        var quote = quote(1, resource, slot, offer, 3);
        assertEquals(29_000, quote.totalCents());
        var held = first.hold(1, "revoked-hold", pricedHold(resource, slot, 3, quote)).booking();
        pricing.revoke(coupon.id(), "领取资格经商家核实无效", 900, false);
        var request = new ActionRequest(held.version(), null, null);
        var rejected = second.confirm(1, held.id(), "revoked-confirm", request);
        assertEquals(409, rejected.httpStatus());
        assertEquals("CANCELLED", rejected.booking().status());
        assertEquals(rejected, first.confirm(1, held.id(), "revoked-confirm", request));
        assertEquals("REVOKED", couponStatus(coupon));
        assertEquals(0, occupied(slot));
        assertCapacityInvariant();
    }

    @Test
    void pricedRescheduleFailurePreservesOldCouponPriceAndBookingThenCheckinConsumesOnce() {
        var resource = resource("DINING_POOL", 20L, 8);
        var oldSlot = slot(resource, tomorrow, 10);
        var now = sql.queryForObject("SELECT UTC_TIMESTAMP(3)", java.sql.Timestamp.class).toInstant();
        var target = slot(resource, now.plus(5, ChronoUnit.MINUTES), 4);
        var offer = offer(resource, 5000);
        var coupon = claim(1, resource, "reschedule", "CASH", 0, 1000, 10_000);
        var quoted = quote(1, resource, oldSlot, offer, 3);
        var held = first.hold(1, "old-priced", pricedHold(resource, oldSlot, 3, quoted)).booking();
        var original = first.confirm(1, held.id(), "old-priced-confirm", new ActionRequest(held.version(), null, null)).booking();
        var moveQuote = pricing.quote(1, new QuoteRequest(resource.id(), target.id(), 4, offer.id(), "AUTO", List.of(), "RESCHEDULE",
                original.id(), original.version(), "WEB", "acceptance"));
        var competitorQuote = quote(2, resource, target, offer, 1);
        var competitor = second.hold(2, "competitor", pricedHold(resource, target, 1, competitorQuote)).booking();
        reject(() -> first.reschedule(1, original.id(), "move-full",
                new RescheduleRequest(original.version(), resource.id(), target.id(), 4, moveQuote.quoteId())));
        var stillOriginal = second.booking(original.id(), 1);
        assertEquals(original.version(), stillOriginal.version());
        assertEquals(original.priceSnapshot(), stillOriginal.priceSnapshot());
        assertEquals(3, occupied(oldSlot));
        assertEquals("RESERVED", couponStatus(coupon));
        second.cancel(2, false, false, competitor.id(), "competitor-cancel", new ActionRequest(competitor.version(), null, null));
        var moved = first.reschedule(1, original.id(), "move-success",
                new RescheduleRequest(original.version(), resource.id(), target.id(), 4, moveQuote.quoteId())).booking();
        assertEquals(19_000, ((Number) moved.priceSnapshot().get("totalCents")).intValue());
        sql.update("UPDATE commerce_user_booking_coupon SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", coupon.id());
        var used = first.checkIn(900, false, moved.id(), "coupon-checkin", new ActionRequest(moved.version(), null, moved.checkinToken()));
        assertEquals("USED", used.booking().status());
        assertEquals("CONSUMED", couponStatus(coupon));
        assertEquals(0, occupied(oldSlot));
        assertEquals(4, occupied(target));
        assertCapacityInvariant();
    }

    @Test
    void couponQuotaIncludesReservedCouponsAndClaimReplaySurvivesIssuanceStop() {
        var resource = resource("COURT", 10L, 4);
        var slot = slot(resource, tomorrow, 1);
        var offer = offer(resource, 5000);
        CouponView firstCoupon = null;
        for (int index = 0; index < 20; index++) {
            var claimed = claim(1, resource, "cap-" + index, "CASH", 0, 100 + index, 10_000);
            if (firstCoupon == null) firstCoupon = claimed;
        }
        var quote = quote(1, resource, slot, offer, 2);
        var held = first.hold(1, "cap-hold", pricedHold(resource, slot, 2, quote)).booking();
        assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM commerce_user_booking_coupon WHERE status='RESERVED'", Integer.class));
        var lastRule = rule(resource, "cap-21", "CASH", 0, 500, 10_000);
        assertThrows(CommerceApiException.class, () -> pricing.claim(1, lastRule.id(), "cap-21"));
        pricing.setIssuance(firstCoupon.rule().id(), "STOPPED", 900, false);
        assertEquals(firstCoupon.id(), pricing.claim(1, firstCoupon.rule().id(), "claim-cap-0").id());
        first.cancel(1, false, false, held.id(), "cap-cancel", new ActionRequest(held.version(), null, null));
        assertEquals(20, sql.queryForObject("SELECT COUNT(*) FROM commerce_user_booking_coupon WHERE status='AVAILABLE'", Integer.class));
        assertCapacityInvariant();
    }

    @Test
    void sameCouponCannotBindTwoConfirmedBookingsAndExpiryCannotTransferIt() {
        var resource = resource("COURT", 10L, 4);
        var oldSlot = slot(resource, tomorrow, 1);
        var target = slot(resource, tomorrow.plus(2, ChronoUnit.HOURS), 1);
        var offer = offer(resource, 5000);
        var coupon = claim(1, resource, "one-binding", "CASH", 0, 1000, 0);
        var firstQuote = quote(1, resource, oldSlot, offer, 2);
        var secondQuote = quote(1, resource, target, offer, 2);
        var held = first.hold(1, "coupon-first", pricedHold(resource, oldSlot, 2, firstQuote)).booking();
        var confirmed = first.confirm(1, held.id(), "coupon-confirm", new ActionRequest(held.version(), null, null)).booking();
        var rejected = assertThrows(CommerceApiException.class,
                () -> second.hold(1, "coupon-second", pricedHold(resource, target, 2, secondQuote)));
        assertEquals("BOOKING_COUPON_UNAVAILABLE", rejected.code());
        assertEquals(0, occupied(target));
        var move = pricing.quote(1, new QuoteRequest(resource.id(), target.id(), 2, offer.id(), "AUTO", List.of(), "RESCHEDULE",
                confirmed.id(), confirmed.version(), "WEB", "coupon-expiry"));
        sql.update("UPDATE commerce_user_booking_coupon SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", coupon.id());
        var expired = assertThrows(CommerceApiException.class, () -> second.reschedule(1, confirmed.id(), "expired-transfer",
                new RescheduleRequest(confirmed.version(), resource.id(), target.id(), 2, move.quoteId())));
        assertEquals("BOOKING_COUPON_UNAVAILABLE", expired.code());
        var unchanged = first.booking(confirmed.id(), 1);
        assertEquals(confirmed.version(), unchanged.version());
        assertEquals(confirmed.slotId(), unchanged.slotId());
        assertEquals(confirmed.priceSnapshot(), unchanged.priceSnapshot());
        assertEquals("RESERVED", couponStatus(coupon));
        assertCapacityInvariant();
    }

    private OfferView offer(ResourceView resource, long unitCents) {
        return pricing.saveOffer(resource.id(), new OfferRequest(null, resource.kind().equals("COURT") ? "COURT_SLOT" : "PER_PERSON_PACKAGE",
                "预约专享套餐", "按预约人数提供", "额外点单另付", unitCents, "OPEN", null), 900, false);
    }

    private RuleView rule(ResourceView resource, String campaign, String type, long threshold, long reduction, int factor) {
        var now = sql.queryForObject("SELECT UTC_TIMESTAMP(3)", java.sql.Timestamp.class).toInstant();
        return pricing.publishRule(resource.shopId(), new RuleRequest(campaign, null, type, threshold, reduction, type.equals("DISCOUNT") ? factor : 0,
                null, true, now.minus(1, ChronoUnit.HOURS), tomorrow.plus(3, ChronoUnit.DAYS),
                now.plus(2, ChronoUnit.DAYS), now.plus(3, ChronoUnit.DAYS)), 900, false);
    }

    private CouponView claim(long user, ResourceView resource, String campaign, String type, long threshold, long reduction, int factor) {
        return pricing.claim(user, rule(resource, campaign, type, threshold, reduction, factor).id(), "claim-" + campaign);
    }

    private QuoteView quote(long user, ResourceView resource, SlotView slot, OfferView offer, int party) {
        return pricing.quote(user, new QuoteRequest(resource.id(), slot.id(), party, offer.id(), "AUTO", List.of(), "CREATE",
                null, null, "WEB", "acceptance"));
    }

    private HoldRequest pricedHold(ResourceView resource, SlotView slot, int party, QuoteView quote) {
        return new HoldRequest(resource.id(), slot.id(), party, quote.quoteId(), quote.source(), quote.requestId());
    }

    private String couponStatus(CouponView coupon) {
        return sql.queryForObject("SELECT status FROM commerce_user_booking_coupon WHERE id=?", String.class, coupon.id());
    }

    private SlotView slot(ResourceView resource, Instant start, int capacity) {
        return resources.saveSlot(Long.parseLong(resource.id()), 900, false,
                new SlotRequest(null, start, start.plus(1, ChronoUnit.HOURS), capacity, "OPEN", null));
    }

    private HoldRequest holdRequest(ResourceView resource, SlotView slot, int party) {
        return new HoldRequest(resource.id(), slot.id(), party, null, "WEB", "acceptance");
    }

    private MutationResult hold(BookingService service, long user, String key, ResourceView resource, SlotView slot, int party) {
        return service.hold(user, key, holdRequest(resource, slot, party));
    }

    private BookingView confirm(long user, ResourceView resource, SlotView slot, int party) {
        var held = first.hold(user, "hold-" + slot.id() + "-" + user, holdRequest(resource, slot, party)).booking();
        return first.confirm(user, held.id(), "confirm-" + held.id(), new ActionRequest(held.version(), null, null)).booking();
    }

    private int occupied(SlotView slot) {
        return sql.queryForObject("SELECT occupied FROM commerce_booking_slot WHERE id=?", Integer.class, slot.id());
    }

    private int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private boolean success(MutationResult result) { return result != null && result.httpStatus() < 300; }

    private void reject(Callable<MutationResult> action) {
        try {
            assertFalse(success(action.call()), "Expected business rejection");
        } catch (CommerceApiException expected) {
            assertTrue(expected.status() >= 400 && expected.status() < 500 || expected.status() == 503);
        } catch (Exception error) {
            fail(error);
        }
    }

    private List<MutationResult> race(int count, IntFunction<MutationResult> operation) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<MutationResult>>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(15, TimeUnit.SECONDS));
                    try { return operation.apply(index); }
                    catch (CommerceApiException rejected) {
                        return new MutationResult(rejected.code(), rejected.status(), rejected.getMessage(), null);
                    }
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<MutationResult>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertCapacityInvariant() {
        assertEquals(0, sql.queryForObject("""
                SELECT COUNT(*) FROM commerce_booking_slot s
                LEFT JOIN (SELECT slot_id,SUM(units) AS n FROM commerce_booking
                  WHERE status IN ('HELD','CONFIRMED','USED','NO_SHOW') GROUP BY slot_id) b ON b.slot_id=s.id
                WHERE s.occupied<>COALESCE(b.n,0) OR s.occupied<0 OR s.occupied>s.capacity
                """, Integer.class));
    }
}
