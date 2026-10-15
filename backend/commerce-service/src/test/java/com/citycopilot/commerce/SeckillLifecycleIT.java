package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.AdmissionAccepted;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 用真实 MySQL、Redis Lua 和 Stream Worker 验证秒杀不变量。
 *
 * <p>只连接显式提供的一次性资源：数据库名必须以 _test 结尾，Redis 必须指定 2 号及以上的独立 DB，
 * 测试开始时会清空该 DB。</p>
 *
 * <p>环境变量：{@code CITYCOPILOT_SECKILL_TEST_DB_URL}（必填）、{@code _DB_USER}、{@code _DB_PASSWORD}、
 * {@code _REDIS_HOST}、{@code _REDIS_PORT}、{@code _REDIS_PASSWORD}、{@code _REDIS_DATABASE}（必填，≥2），
 * 前缀均为 {@code CITYCOPILOT_SECKILL_TEST}。运行：{@code mvn -pl backend/commerce-service -am test -Dtest=SeckillLifecycleIT}。</p>
 */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_SECKILL_TEST_DB_URL", matches = "jdbc:mysql:.+")
class SeckillLifecycleIT {
    private static final AtomicLong NEXT_VOUCHER = new AtomicLong(910_000);
    private static HikariDataSource pool;
    private static LettuceConnectionFactory redisFactory;
    private static JdbcTemplate sql;
    private static StringRedisTemplate redis;
    private static CommerceRedisKeyspace keyspace;
    private static CommerceRedisReplicationExecutor replicatedRedis;
    private static CommerceRedisInventoryBootstrap inventory;
    private static CommerceRedisCampaignMetadata metadata;
    private static CommerceOrderAdmissionService admissions;
    private static CommerceOrderService orders;
    private static CommerceOrderStreamWorker worker;
    private static CommerceConsistencyService consistency;
    private static CommercePaymentService payments;
    private static CommerceReadCache readCache;
    private static RedisMessageListenerContainer cacheListener;
    private static CommerceVoucherService vouchers;

    @BeforeAll
    static void connect() throws Exception {
        pool = pool();
        try (var connection = pool.getConnection()) {
            if (!connection.getCatalog().endsWith("_test")) {
                throw new IllegalStateException("Seckill acceptance requires a disposable database ending in _test");
            }
        }
        sql = new JdbcTemplate(pool);
        Path root = Path.of("../..").toAbsolutePath().normalize();
        org.flywaydb.core.Flyway.configure().dataSource(pool)
                .locations("filesystem:" + root.resolve("deploy/mysql/commerce"))
                .cleanDisabled(true).load().migrate();
        for (String table : List.of("commerce_payment", "commerce_admission_ledger", "commerce_dead_letter", "commerce_order_recovery",
                "commerce_reservation", "commerce_order", "commerce_outbox_event",
                "commerce_seckill_voucher", "commerce_voucher")) {
            sql.update("DELETE FROM " + table);
        }

        int database = Integer.parseInt(System.getenv("CITYCOPILOT_SECKILL_TEST_REDIS_DATABASE"));
        if (database < 2) {
            throw new IllegalStateException("Seckill acceptance flushes its Redis DB; use database 2 or above");
        }
        var redisConfig = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("CITYCOPILOT_SECKILL_TEST_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("CITYCOPILOT_SECKILL_TEST_REDIS_PORT", "6380")));
        redisConfig.setDatabase(database);
        redisConfig.setPassword(System.getenv().getOrDefault("CITYCOPILOT_SECKILL_TEST_REDIS_PASSWORD", ""));
        redisFactory = new LettuceConnectionFactory(redisConfig);
        redisFactory.afterPropertiesSet();
        redisFactory.start();
        redis = new StringRedisTemplate(redisFactory);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });

        var transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
        var metrics = new SimpleMeterRegistry();
        keyspace = new CommerceRedisKeyspace(1, "it");
        readCache = new CommerceReadCache(redis, keyspace, json);
        cacheListener = readCache.inventoryCacheListener(redisFactory);
        cacheListener.afterPropertiesSet();
        cacheListener.start();
        replicatedRedis = new CommerceRedisReplicationExecutor(redisFactory, metrics, 0, 1_000);
        var ids = new CommerceOrderIdGenerator(sql, transactions, 1024);
        var durable = new CommerceAdmissionDurabilityService(sql, ids, keyspace, metrics,
                100, 0, 30_000, 5_000, 20, 50_000, 300_000, 1, "seckill-it", readCache);
        var recovery = new CommerceOrderRecoveryStore(sql);
        inventory = new CommerceRedisInventoryBootstrap(sql, redis, keyspace, false);
        metadata = new CommerceRedisCampaignMetadata(sql, redis, keyspace, true, 300_000);
        admissions = new CommerceOrderAdmissionService(sql, transactions, ids, durable, replicatedRedis, keyspace, readCache);
        orders = new CommerceOrderService(sql, redis, transactions, json, durable, keyspace, recovery, mock(PlaceAuthorityClient.class));
        payments = new CommercePaymentService(sql, transactions, orders,
                new CommerceJobLease(sql, transactions), "integration-payment-signing-secret", true);
        worker = new CommerceOrderStreamWorker(sql, redis, transactions, json, durable, keyspace,
                orders, admissions, recovery, "seckill-it", true, 100, 10);
        worker.initializeStream();
        consistency = new CommerceConsistencyService(sql, redis, json, keyspace, replicatedRedis);
        vouchers = new CommerceVoucherService(sql, mock(PlaceAuthorityClient.class), ids, inventory, metadata, readCache);
    }

    @AfterAll
    static void close() throws Exception {
        if (cacheListener != null) cacheListener.destroy();
        if (replicatedRedis != null) replicatedRedis.close();
        if (redisFactory != null) redisFactory.destroy();
        if (pool != null) pool.close();
    }

    @Test
    void concurrentSegmentAllocationAcrossInstancesNeitherDeadlocksNorOverlaps() throws Exception {
        // 号段大小为 1，每次取号都换段，模拟两个实例同时耗尽号段的最坏情况。
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        var instances = List.of(new CommerceOrderIdGenerator(sql, transactions, 1),
                new CommerceOrderIdGenerator(sql, transactions, 1));
        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<Long>>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                CommerceOrderIdGenerator ids = instances.get(t % 2);
                results.add(threads.submit(() -> {
                    List<Long> taken = new ArrayList<>();
                    for (int i = 0; i < 50; i++) taken.add(ids.nextId());
                    return taken;
                }));
            }
            Set<Long> unique = new HashSet<>();
            for (Future<List<Long>> result : results) unique.addAll(result.get(60, TimeUnit.SECONDS));
            assertThat(unique).hasSize(400);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void batchCreatesOrdersForMixedPreparedAndAcceptedAdmissions() {
        long voucherId = seckillVoucher(10);
        List<Long> orderIds = admitUsers(voucherId, 60_001, 6);
        // Lua 写入 Stream 早于 markAccepted，Worker 读到 PREPARED 是正常竞争，批量路径必须接受。
        sql.update("UPDATE commerce_admission_ledger SET status='PREPARED',accepted_at=NULL WHERE order_id IN (?,?)",
                orderIds.get(0), orderIds.get(3));

        drainUntilOrders(voucherId, 6);

        assertThat(databaseStock(voucherId)).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM commerce_admission_ledger WHERE voucher_id=? AND status='ORDER_CREATED'",
                voucherId)).isEqualTo(6);
        // 批量路径用行锁代替逐条 claim，claim_version 不变，证明这些订单确实走了批量事务。
        assertThat(count("SELECT COUNT(*) FROM commerce_order_recovery WHERE voucher_id=? AND phase='ORDER_CREATED' AND claim_version=0",
                voucherId)).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM commerce_reservation WHERE voucher_id=? AND status='ORDER_CREATED'",
                voucherId)).isEqualTo(6);
        assertThat(orderCreatedEvents(voucherId)).isEqualTo(6);
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void concurrentAndRepeatedBatchDeliveriesCreateEachOrderOnce() throws Exception {
        long voucherId = seckillVoucher(5);
        admitUsers(voucherId, 61_001, 5);
        String stream = keyspace.stream(voucherId);
        var records = streamRecords(voucherId);

        concurrently(2, index -> {
            worker.processBatch(stream, records);
            return "done";
        });
        // 已终态的记录再次投递时只做收尾。
        worker.processBatch(stream, records);

        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(5);
        assertThat(databaseStock(voucherId)).isZero();
        assertThat(orderCreatedEvents(voucherId)).isEqualTo(5);
        assertThat(redis.opsForSet().members(keyspace.inflight(voucherId))).isEmpty();
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void batchSkipsARecordClaimedByAnotherWorkerUntilItsClaimExpires() {
        long voucherId = seckillVoucher(3);
        admitUsers(voucherId, 62_001, 3);
        String stream = keyspace.stream(voucherId);
        var records = streamRecords(voucherId);
        MapRecord<String, Object, Object> claimed = records.get(0);
        String sourceId = keyspace.recoverySourceId(stream, claimed.getId().getValue());
        sql.update("""
                INSERT INTO commerce_order_recovery(
                    source_id,source_type,order_id,voucher_id,user_id,idempotency_key,payload_json,phase,
                    claim_token,claim_owner,claimed_until
                ) VALUES (?,'STREAM_ORDER',?,?,?,?,'{}','RECEIVED',?,'other-worker',
                          TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3)))
                """, sourceId, field(claimed, "orderId"), voucherId, field(claimed, "userId"),
                claimed.getValue().get("idempotencyKey"), UUID.randomUUID().toString());

        worker.processBatch(stream, records);

        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT claim_owner FROM commerce_order_recovery WHERE source_id=?",
                String.class, sourceId)).isEqualTo("other-worker");

        sql.update("UPDATE commerce_order_recovery SET claimed_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE source_id=?",
                sourceId);
        worker.processBatch(stream, List.of(claimed));

        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(3);
        assertThat(databaseStock(voucherId)).isZero();
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void failureAfterTheStockDeductionRollsBackTheWholeBatch() {
        long voucherId = seckillVoucher(4);
        List<Long> orderIds = admitUsers(voucherId, 63_001, 4);
        String stream = keyspace.stream(voucherId);
        var records = streamRecords(voucherId);
        sql.execute("""
                CREATE TRIGGER it_fail_batch_outbox BEFORE INSERT ON commerce_outbox_event FOR EACH ROW
                BEGIN
                  IF NEW.aggregate_id = '%d' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected outbox failure';
                  END IF;
                END
                """.formatted(orderIds.get(3)));
        try {
            worker.processBatch(stream, records);

            // Outbox 写在扣库存之后；整批回滚后库存、预留、订单、Recovery 和账本都不应有痕迹。
            assertThat(databaseStock(voucherId)).isEqualTo(4);
            assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isZero();
            assertThat(count("SELECT COUNT(*) FROM commerce_reservation WHERE voucher_id=?", voucherId)).isZero();
            assertThat(count("SELECT COUNT(*) FROM commerce_order_recovery WHERE voucher_id=?", voucherId)).isZero();
            assertThat(count("SELECT COUNT(*) FROM commerce_admission_ledger WHERE voucher_id=? AND status='ORDER_CREATED'",
                    voucherId)).isZero();
        } finally {
            sql.execute("DROP TRIGGER IF EXISTS it_fail_batch_outbox");
        }

        worker.processBatch(stream, records);

        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(4);
        assertThat(databaseStock(voucherId)).isZero();
        assertThat(orderCreatedEvents(voucherId)).isEqualTo(4);
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void committedBatchWhoseRedisFinalizationWasLostIsOnlyFinalizedOnTakeover() {
        long voucherId = seckillVoucher(3);
        admitUsers(voucherId, 64_001, 3);
        String stream = keyspace.stream(voucherId);
        var records = streamRecords(voucherId);
        // 模拟批量事务已提交、进程在 Redis 收尾前崩溃。
        var batch = records.stream().map(record -> new CommerceOrderService.BatchOrder(
                keyspace.recoverySourceId(stream, record.getId().getValue()), record.getId().getValue(),
                field(record, "orderId"), voucherId, field(record, "userId"),
                String.valueOf(record.getValue().get("idempotencyKey")), "{}")).toList();
        assertThat(orders.createOrders(voucherId, batch).committed()).hasSize(3);
        assertThat(redis.opsForSet().members(keyspace.inflight(voucherId))).hasSize(3);

        worker.processBatch(stream, records);

        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(3);
        assertThat(databaseStock(voucherId)).isZero();
        assertThat(orderCreatedEvents(voucherId)).isEqualTo(3);
        assertThat(redis.opsForSet().members(keyspace.inflight(voucherId))).isEmpty();
        assertThat(streamRecords(voucherId)).isEmpty();
    }

    @Test
    void batchAboveDatabaseStockFallsBackAndKeepsDurableAcceptancePromises() {
        long voucherId = seckillVoucher(5);
        List<Long> orderIds = admitUsers(voucherId, 65_001, 5);
        sql.update("UPDATE commerce_admission_ledger SET status='PREPARED',accepted_at=NULL WHERE order_id=?",
                orderIds.get(4));
        // MySQL 库存比 Redis 少 2：一次扣 5 份失败，整批回退逐条路径逐单判定。
        sql.update("UPDATE commerce_seckill_voucher SET stock=3 WHERE voucher_id=?", voucherId);
        String stream = keyspace.stream(voucherId);

        worker.processBatch(stream, streamRecords(voucherId));

        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(3);
        assertThat(databaseStock(voucherId)).isZero();
        // 已持久受理的保留承诺，留在 Pending 等待恢复，不退库存。
        assertThat(sql.queryForObject("SELECT status FROM commerce_admission_ledger WHERE order_id=?",
                String.class, orderIds.get(3))).isEqualTo("ACCEPTED");
        assertThat(sql.queryForObject("SELECT phase FROM commerce_order_recovery WHERE order_id=?",
                String.class, orderIds.get(3))).isEqualTo("RECEIVED");
        // 未持久受理的按现有语义补偿 Redis 准入。
        assertThat(sql.queryForObject("SELECT phase FROM commerce_order_recovery WHERE order_id=?",
                String.class, orderIds.get(4))).isEqualTo("DEAD_LETTERED");

        // 补回库存后，保留的受理承诺兑现为订单。
        sql.update("UPDATE commerce_seckill_voucher SET stock=1 WHERE voucher_id=?", voucherId);
        worker.processBatch(stream, streamRecords(voucherId));
        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(4);
    }

    @Test
    void acceptedOrderIsQueryableBeforeTheStreamWorkerPersistsIt() {
        long voucherId = seckillVoucher(1);
        long userId = 90_001;
        long orderId = Long.parseLong(orderIdOf(seckillResult(voucherId, userId, "accepted-query")));

        var pending = orders.order(orderId, userId, false, false);
        assertThat(pending.id()).isEqualTo(String.valueOf(orderId));
        assertThat(pending.userId()).isEqualTo(userId);
        assertThat(pending.voucherId()).isEqualTo(voucherId);
        assertThat(pending.state()).isEqualTo("processing");
        assertThat(pending.payValue()).isNull();
        assertThatThrownBy(() -> orders.order(orderId, userId + 1, false, false))
                .isInstanceOfSatisfying(CommerceApiException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("COMMERCE_ORDER_NOT_FOUND"));
        assertThatThrownBy(() -> payments.start(orderId, userId, 1, false, false))
                .isInstanceOfSatisfying(CommerceApiException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("COMMERCE_ORDER_PROCESSING"));
        assertThatThrownBy(() -> orders.transition(orderId, userId, "cancel", false, false))
                .isInstanceOfSatisfying(CommerceApiException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("COMMERCE_ORDER_PROCESSING"));
        sql.update("UPDATE commerce_admission_ledger SET status='REPAIR_REQUIRED' WHERE order_id=?", orderId);
        assertThatThrownBy(() -> orders.order(orderId, userId, false, false))
                .isInstanceOfSatisfying(CommerceApiException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("COMMERCE_ADMISSION_REPAIR_REQUIRED"));
        sql.update("UPDATE commerce_admission_ledger SET status='ACCEPTED' WHERE order_id=?", orderId);

        drainUntilOrders(voucherId, 1);
        assertThat(orders.order(orderId, userId, false, false).state()).isEqualTo("unpaid");
        assertThat(orders.order(orderId, userId, false, false).payValue()).isEqualTo(100);
    }

    @Test
    void admissionRecoveryCannotRestoreBuyerAfterConcurrentCancellation() throws Exception {
        long voucherId = seckillVoucher(1);
        long userId = 90_002;
        long orderId = Long.parseLong(orderIdOf(seckillResult(voucherId, userId, "recover-cancel")));
        sql.update("UPDATE commerce_admission_ledger SET accepted_at=UTC_TIMESTAMP(3)-INTERVAL 1 DAY WHERE order_id=?", orderId);
        MapRecord<String, Object, Object> record = redis.opsForStream()
                .range(keyspace.stream(voucherId), Range.unbounded()).stream()
                .filter(candidate -> String.valueOf(orderId).equals(String.valueOf(candidate.getValue().get("orderId"))))
                .findFirst().orElseThrow();
        var orderChecked = new CountDownLatch(1);
        var continueRecovery = new CountDownLatch(1);
        JdbcTemplate coordinated = new JdbcTemplate(pool) {
            @Override
            public List<java.util.Map<String, Object>> queryForList(String query, Object... arguments) {
                var rows = super.queryForList(query, arguments);
                if (query.contains("SELECT id,user_id,voucher_id,idempotency_key")) {
                    orderChecked.countDown();
                    try {
                        if (!continueRecovery.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Admission recovery coordination timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return rows;
            }
        };
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        var ids = new CommerceOrderIdGenerator(sql, transactions, 1024);
        var durable = new CommerceAdmissionDurabilityService(sql, ids, keyspace, new SimpleMeterRegistry(),
                1, 0, 30_000, 5_000, 20, 50_000, 300_000, 1, "recovery-race-it", readCache);
        var contender = new CommerceOrderAdmissionService(coordinated, transactions, ids, durable, replicatedRedis, keyspace, readCache);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> recovery = executor.submit(contender::recoverDurableAdmissions);
            assertThat(orderChecked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> cancellation = executor.submit(() -> {
                worker.process(record);
                orders.transition(orderId, userId, "cancel", false, false);
            });
            // 取消先完成，或落单被账本行锁挡住；观察真实数据库等待，再放开恢复者。
            boolean competingWriteObserved = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (cancellation.isDone() || sql.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='commerce_admission_ledger'
                        """, Integer.class) > 0) {
                    competingWriteObserved = true;
                    break;
                }
            }
            assertThat(competingWriteObserved).isTrue();
            continueRecovery.countDown();
            recovery.get(10, TimeUnit.SECONDS);
            cancellation.get(10, TimeUnit.SECONDS);
            assertThat(redis.opsForSet().isMember(keyspace.buyers(voucherId), String.valueOf(userId))).isFalse();
            assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
            assertThat(seckillResult(voucherId, userId, "after-recovery-cancel").code()).isEqualTo("COMMERCE_ORDER_ACCEPTED");
            drainUntilOrders(voucherId, 2);
        } finally {
            continueRecovery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentBuyersNeverOversellAndInventoryEndsConsistent() throws Exception {
        long voucherId = seckillVoucher(5);

        List<String> outcomes = concurrently(40, i -> seckill(voucherId, 10_000 + i, "buyer-" + i));

        assertThat(outcomes.stream().filter("COMMERCE_ORDER_ACCEPTED"::equals)).hasSize(5);
        assertThat(outcomes.stream().filter("SECKILL_STOCK_NOT_ENOUGH"::equals)).hasSize(35);
        drainUntilOrders(voucherId, 5);
        assertThat(databaseStock(voucherId)).isZero();
        assertThat(redis.opsForValue().get(keyspace.stock(voucherId))).isEqualTo("0");
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void oneUserGetsOneOrderAcrossRetriesAndDifferentKeys() throws Exception {
        long voucherId = seckillVoucher(10);

        List<String> sameKey = concurrently(8, i -> orderIdOf(seckillResult(voucherId, 20_001, "same-key")));
        List<String> otherKeys = concurrently(8, i -> seckill(voucherId, 20_001, "other-key-" + i));

        assertThat(new HashSet<>(sameKey)).hasSize(1);
        assertThat(otherKeys).containsOnly("SECKILL_DUPLICATE_ORDER");
        drainUntilOrders(voucherId, 1);
        assertThat(databaseStock(voucherId)).isEqualTo(9);
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void redeliveredStreamRecordCreatesTheOrderAndEventOnce() {
        long voucherId = seckillVoucher(3);
        String orderId = orderIdOf(seckillResult(voucherId, 30_001, "redelivery"));
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(keyspace.stream(voucherId), Range.unbounded());
        MapRecord<String, Object, Object> record = records.stream()
                .filter(candidate -> orderId.equals(String.valueOf(candidate.getValue().get("orderId"))))
                .findFirst().orElseThrow();

        worker.process(record);
        worker.process(record);

        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM commerce_outbox_event WHERE aggregate_id=? AND event_type='commerce.order.created'",
                orderId)).isEqualTo(1);
        assertThat(databaseStock(voucherId)).isEqualTo(2);
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void retryAfterLostAcceptanceResolvesToTheSameOrderWithoutSecondDeduction() {
        long voucherId = seckillVoucher(3);
        String orderId = orderIdOf(seckillResult(voucherId, 40_001, "lost-ack"));
        // 模拟 Lua 已扣库存、但进程在 PREPARED→ACCEPTED 之前崩溃。
        sql.update("UPDATE commerce_admission_ledger SET status='PREPARED',accepted_at=NULL WHERE order_id=?",
                Long.parseLong(orderId));

        AdmissionAccepted retried = seckillResult(voucherId, 40_001, "lost-ack");

        assertThat(retried.orderId()).isEqualTo(orderId);
        assertThat(retried.code()).isEqualTo("IDEMPOTENT_REPLAY");
        assertThat(sql.queryForObject("SELECT status FROM commerce_admission_ledger WHERE order_id=?",
                String.class, Long.parseLong(orderId))).isEqualTo("ACCEPTED");
        assertThat(redis.opsForValue().get(keyspace.stock(voucherId))).isEqualTo("2");
        drainUntilOrders(voucherId, 1);
        assertThat(databaseStock(voucherId)).isEqualTo(2);
    }

    @Test
    void cancellationReturnsStockToMysqlAndRedisExactlyOnce() {
        long voucherId = seckillVoucher(1);
        String orderId = orderIdOf(seckillResult(voucherId, 50_001, "cancel-me"));
        drainUntilOrders(voucherId, 1);
        assertThat(seckill(voucherId, 50_002, "sold-out")).isEqualTo("SECKILL_STOCK_NOT_ENOUGH");

        var cancelled = orders.transition(Long.parseLong(orderId), 50_001, "cancel", false, false);

        assertThat(cancelled.state()).isEqualTo("cancelled");
        assertThat(cancelled.inventoryRecovery()).isNull();
        assertThat(databaseStock(voucherId)).isEqualTo(1);
        assertThat(redis.opsForValue().get(keyspace.stock(voucherId))).isEqualTo("1");
        assertThat(sql.queryForObject("SELECT phase FROM commerce_order_recovery WHERE source_type='STATE_TRANSITION' AND order_id=?",
                String.class, Long.parseLong(orderId))).isEqualTo("RESTORED");
        awaitSoldOutClear(voucherId);
        assertThat(seckill(voucherId, 50_002, "after-cancel")).isEqualTo("COMMERCE_ORDER_ACCEPTED");
    }

    @Test
    void expiryReturnsInventoryAndTheSameBuyerCanOrderAgain() {
        long voucherId = seckillVoucher(1);
        long userId = 60_001;
        String orderId = orderIdOf(seckillResult(voucherId, userId, "expires"));
        drainUntilOrders(voucherId, 1);
        assertThat(sql.queryForObject("SELECT TIMESTAMPDIFF(SECOND,create_time,pay_deadline) FROM commerce_order WHERE id=?",
                Long.class, orderId)).isEqualTo(900);
        assertThat(orders.expireDueOrders()).isZero();
        sql.update("UPDATE commerce_order SET pay_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", orderId);

        assertThat(orders.expireDueOrders()).isEqualTo(1);
        assertThat(orders.expireDueOrders()).isZero();

        assertThat(orders.order(Long.parseLong(orderId), userId, false, false).statusText()).isEqualTo("超时已取消");
        assertThat(sql.queryForObject("SELECT close_reason FROM commerce_order WHERE id=?", String.class, orderId))
                .isEqualTo("PAY_TIMEOUT");
        assertThat(databaseStock(voucherId)).isEqualTo(1);
        assertThat(redis.opsForValue().get(keyspace.stock(voucherId))).isEqualTo("1");
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
        assertThat(orderIdOf(seckillResult(voucherId, userId, "expires"))).isEqualTo(orderId);
        assertThat(orderIdOf(seckillResult(voucherId, userId, "buy-again"))).isNotEqualTo(orderId);
        drainUntilOrders(voucherId, 2);
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void signedPaymentCallbackIsIdempotentAndUserRefundIsAtomic() {
        long voucherId = seckillVoucher(1);
        long orderId = Long.parseLong(orderIdOf(seckillResult(voucherId, 65_001, "pay-once")));
        drainUntilOrders(voucherId, 1);
        assertThat(payments.start(orderId, 65_001, 1, false, false).state()).isEqualTo("unpaid");
        payments.start(orderId, 65_001, 1, false, false);
        var callback = paymentCallback(orderId);
        assertThatThrownBy(() -> payments.callback(callback, "wrong-signature"))
                .isInstanceOfSatisfying(CommerceApiException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMERCE_PAYMENT_SIGNATURE_INVALID"));
        assertThat(orders.order(orderId, 65_001, false, false).state()).isEqualTo("unpaid");
        String signature = payments.sign(callback);
        assertThat(payments.callback(callback, signature)).isEqualTo("PAID");
        assertThat(payments.callback(callback, signature)).isEqualTo("PAID");
        assertThat(count("SELECT COUNT(*) FROM commerce_payment WHERE order_id=?", orderId)).isEqualTo(1);
        assertThat(payments.refund(orderId, 65_001, false, false).state()).isEqualTo("refunded");
        assertThat(payments.refund(orderId, 65_001, false, false).state()).isEqualTo("refunded");
        assertThat(payments.callback(callback, signature)).isEqualTo("PAID");
        assertThat(databaseStock(voucherId)).isEqualTo(1);
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
    }

    @Test
    void endedCampaignArchivesRedisStateAndLaterRefundOnlyReturnsMysqlStock() {
        long voucherId = seckillVoucher(2);
        long orderId = Long.parseLong(orderIdOf(seckillResult(voucherId, 68_001, "archive-paid")));
        drainUntilOrders(voucherId, 1);
        payments.start(orderId, 68_001, 1, false, false);
        var callback = paymentCallback(orderId);
        assertThat(payments.callback(callback, payments.sign(callback))).isEqualTo("PAID");
        sql.update("UPDATE commerce_seckill_voucher SET end_time=UTC_TIMESTAMP(3)-INTERVAL 2 DAY WHERE voucher_id=?", voucherId);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        sql.update("DELETE FROM commerce_job_lease WHERE job_name='stock-reconciliation'");
        var job = new CommerceReconciliationJob(sql, redis, consistency, metadata,
                JsonMapper.builderWithJackson2Defaults().build(), new CommerceJobLease(sql, transactions),
                keyspace, java.time.Duration.ofDays(1));
        try {
            job.reconcile();
            assertThat(count("SELECT COUNT(*) FROM commerce_seckill_voucher WHERE voucher_id=? AND redis_archived_at IS NOT NULL",
                    voucherId)).isEqualTo(1);
            assertThat(redis.hasKey(keyspace.stock(voucherId))).isFalse();
            assertThat(redis.hasKey(keyspace.buyers(voucherId))).isFalse();
            assertThat(redis.hasKey(keyspace.campaignMeta(voucherId))).isFalse();

            // 归档后的退款只返还 MySQL 库存，回补记录正常完成，不进入人工修复。
            int stockBefore = databaseStock(voucherId);
            assertThat(payments.refund(orderId, 68_001, false, false).state()).isEqualTo("refunded");
            assertThat(databaseStock(voucherId)).isEqualTo(stockBefore + 1);
            assertThat(sql.queryForObject("SELECT phase FROM commerce_order_recovery WHERE order_id=? AND source_type='STATE_TRANSITION'",
                    String.class, orderId)).isEqualTo("RESTORED");

            // 抢已归档活动是确定的“已结束”，拒绝持久化，不会留下待恢复的准入。
            assertThat(seckill(voucherId, 68_002, "archive-late")).isEqualTo("SECKILL_ENDED");
            assertThat(sql.queryForObject("SELECT status FROM commerce_admission_ledger WHERE user_id=? AND idempotency_key=?",
                    String.class, 68_002, "archive-late")).isEqualTo("REJECTED");
        } finally {
            sql.update("DELETE FROM commerce_job_lease WHERE job_name='stock-reconciliation'");
        }
    }

    @Test
    void lateCallbackRefundsWithoutRevivingOrderAndPendingSimulationSurvivesRestart() {
        long voucherId = seckillVoucher(2);
        long lateOrder = Long.parseLong(orderIdOf(seckillResult(voucherId, 66_001, "late-payment")));
        long restartOrder = Long.parseLong(orderIdOf(seckillResult(voucherId, 66_002, "restart-payment")));
        drainUntilOrders(voucherId, 2);
        payments.start(lateOrder, 66_001, 1, false, false);
        payments.start(restartOrder, 66_002, 1, false, false);
        sql.update("UPDATE commerce_order SET pay_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", lateOrder);
        orders.expireDueOrders();
        var callback = paymentCallback(lateOrder);
        assertThat(payments.callback(callback, payments.sign(callback))).isEqualTo("REFUND_PENDING");
        sql.update("UPDATE commerce_payment SET callback_due_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE order_id=?", restartOrder);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        var restarted = new CommercePaymentService(sql, transactions, orders,
                new CommerceJobLease(sql, transactions), "integration-payment-signing-secret", true);
        restarted.processPending();
        assertThat(orders.order(lateOrder, 66_001, false, false).state()).isEqualTo("cancelled");
        assertThat(sql.queryForObject("SELECT status FROM commerce_payment WHERE order_id=?", String.class, lateOrder))
                .isEqualTo("REFUNDED");
        assertThat(orders.order(restartOrder, 66_002, false, false).state()).isEqualTo("paid");
        assertThat(databaseStock(voucherId)).isEqualTo(1);
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);

        sql.update("UPDATE commerce_payment SET status='PENDING' WHERE order_id=?", restartOrder);
        var issue = restarted.review(paymentCallback(restartOrder).paymentId());
        assertThat(issue.issue()).isNotNull();
        assertThatThrownBy(() -> restarted.repair(issue.paymentId(), "stale-evidence"))
                .isInstanceOf(CommerceApiException.class);
        assertThat(restarted.repair(issue.paymentId(), issue.evidenceHash()).issue()).isNull();
    }

    private static CommercePaymentService.Callback paymentCallback(long orderId) {
        String paymentId = sql.queryForObject("SELECT payment_id FROM commerce_payment WHERE order_id=?", String.class, orderId);
        return new CommercePaymentService.Callback(paymentId, "sim:" + paymentId, 100);
    }

    @Test
    void failedCallbackDoesNotBlockLaterPaymentsOrRefundsAndStillReportsFailure() {
        long voucherId = seckillVoucher(3);
        long brokenOrder = Long.parseLong(orderIdOf(seckillResult(voucherId, 66_101, "broken-callback")));
        long paidOrder = Long.parseLong(orderIdOf(seckillResult(voucherId, 66_102, "following-callback")));
        long refundOrder = Long.parseLong(orderIdOf(seckillResult(voucherId, 66_103, "following-refund")));
        drainUntilOrders(voucherId, 3);
        payments.start(brokenOrder, 66_101, 1, false, false);
        payments.start(paidOrder, 66_102, 1, false, false);
        payments.start(refundOrder, 66_103, 1, false, false);
        orders.transition(refundOrder, 66_103, "cancel", false, false);
        var refund = paymentCallback(refundOrder);
        assertThat(payments.callback(refund, payments.sign(refund))).isEqualTo("REFUND_PENDING");
        sql.update("""
                UPDATE commerce_payment SET channel_transaction_id='broken-binding',
                    callback_due_at=UTC_TIMESTAMP(3)-INTERVAL 2 SECOND WHERE order_id=?
                """, brokenOrder);
        sql.update("UPDATE commerce_payment SET callback_due_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE order_id=?", paidOrder);
        sql.update("DELETE FROM commerce_job_lease WHERE job_name='simulated-payments'");
        try {
            assertThatThrownBy(payments::processPending)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(paymentCallback(brokenOrder).paymentId())
                    .hasCauseInstanceOf(CommerceApiException.class)
                    .satisfies(error -> assertThat(((CommerceApiException) error.getCause()).code())
                            .isEqualTo("COMMERCE_PAYMENT_TRANSACTION_CONFLICT"));
            assertThat(orders.order(brokenOrder, 66_101, false, false).state()).isEqualTo("unpaid");
            assertThat(orders.order(paidOrder, 66_102, false, false).state()).isEqualTo("paid");
            assertThat(sql.queryForObject("SELECT status FROM commerce_payment WHERE order_id=?", String.class, refundOrder))
                    .isEqualTo("REFUNDED");
            assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
        } finally {
            sql.update("UPDATE commerce_payment SET channel_transaction_id=NULL,callback_due_at=NULL WHERE order_id=?", brokenOrder);
            sql.update("DELETE FROM commerce_job_lease WHERE job_name='simulated-payments'");
        }
    }

    @Test
    void reconciliationStopsWhenAnotherWorkerTakesTheLease() {
        long voucherId = seckillVoucher(1);
        redis.delete(keyspace.campaignMeta(voucherId));
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        var successor = new CommerceJobLease(sql, transactions);
        JdbcTemplate pausedScanner = new JdbcTemplate(pool) {
            @Override
            public <T> List<T> queryForList(String query, Class<T> type, Object... args) {
                List<T> result = super.queryForList(query, type, args);
                // 巡检列表读出后、处理第一张券前，让后继实例接管租约。
                if (query.contains("end_time > CURRENT_TIMESTAMP(3)") && args[1].equals(0L)) {
                    sql.update("UPDATE commerce_job_lease SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE job_name='stock-reconciliation'");
                    assertThat(successor.acquire("stock-reconciliation")).isPresent();
                }
                return result;
            }
        };
        sql.update("DELETE FROM commerce_job_lease WHERE job_name='stock-reconciliation'");
        var job = new CommerceReconciliationJob(pausedScanner, redis, consistency, metadata,
                JsonMapper.builderWithJackson2Defaults().build(), new CommerceJobLease(sql, transactions),
                keyspace, java.time.Duration.ofDays(1));
        try {
            assertThatThrownBy(job::reconcile).isInstanceOf(CommerceJobLease.LeaseLostException.class);
            assertThat(redis.hasKey(keyspace.campaignMeta(voucherId))).isFalse();
            successor.acquire("stock-reconciliation").orElseThrow()
                    .runFenced(() -> metadata.refreshAfterCommit(voucherId));
            assertThat(redis.hasKey(keyspace.campaignMeta(voucherId))).isTrue();
        } finally {
            sql.update("DELETE FROM commerce_job_lease WHERE job_name='stock-reconciliation'");
        }
    }

    @Test
    void paymentRepairRejectsEvidenceChangedBeforeItAcquiresOrderLock() throws Exception {
        long voucherId = seckillVoucher(1);
        long orderId = Long.parseLong(orderIdOf(seckillResult(voucherId, 67_001, "repair-race")));
        drainUntilOrders(voucherId, 1);
        payments.start(orderId, 67_001, 1, false, false);
        var callback = paymentCallback(orderId);
        payments.callback(callback, payments.sign(callback));
        sql.update("UPDATE commerce_payment SET status='PENDING' WHERE order_id=?", orderId);
        var evidence = payments.review(callback.paymentId());
        var snapshotTaken = new CountDownLatch(1);
        var continueRepair = new CountDownLatch(1);
        JdbcTemplate coordinated = new JdbcTemplate(pool) {
            @Override
            public <T> List<T> queryForList(String query, Class<T> type, Object... arguments) {
                List<T> rows = super.queryForList(query, type, arguments);
                if (query.startsWith("SELECT order_id FROM commerce_payment")) {
                    snapshotTaken.countDown();
                    try {
                        if (!continueRepair.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Repair coordination timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return rows;
            }
        };
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
        var contender = new CommercePaymentService(coordinated, transactions, orders,
                new CommerceJobLease(sql, transactions), "integration-payment-signing-secret", true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> repair = executor.submit(() -> contender.repair(evidence.paymentId(), evidence.evidenceHash()));
            assertThat(snapshotTaken.await(10, TimeUnit.SECONDS)).isTrue();
            payments.repair(evidence.paymentId(), evidence.evidenceHash());
            payments.refund(orderId, 67_001, false, false);
            continueRepair.countDown();
            assertThatThrownBy(() -> repair.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(CommerceApiException.class)
                    .satisfies(error -> assertThat(((CommerceApiException) error.getCause()).code()).isEqualTo("COMMERCE_STALE_EVIDENCE"));
            assertThat(payments.review(evidence.paymentId()).paymentState()).isEqualTo("REFUNDED");
        } finally {
            continueRepair.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void paymentAndExpiryChooseExactlyOneOutcome() throws Exception {
        for (int round = 0; round < 8; round++) {
            long voucherId = seckillVoucher(1);
            long userId = 70_001 + round;
            long orderId = Long.parseLong(orderIdOf(seckillResult(voucherId, userId, "race-" + round)));
            drainUntilOrders(voucherId, 1);
            payments.start(orderId, userId, 1, false, false);
            var callback = paymentCallback(orderId);
            sql.update("UPDATE commerce_order SET pay_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?", orderId);

            concurrently(2, index -> {
                if (index == 1) return String.valueOf(orders.expireDueOrders());
                try {
                    return payments.callback(callback, payments.sign(callback));
                } catch (CommerceApiException rejected) {
                    assertThat(rejected.code()).isEqualTo("COMMERCE_ORDER_STATE_CONFLICT");
                    return rejected.code();
                }
            });

            int state = orders.order(orderId, userId, false, false).status();
            assertThat(state).isIn(2, 4);
            assertThat(databaseStock(voucherId)).isEqualTo(state == 4 ? 1 : 0);
            assertThat(count("SELECT COUNT(*) FROM commerce_outbox_event WHERE aggregate_id=? AND event_type IN ('commerce.order.pay','commerce.order.cancel')",
                    String.valueOf(orderId))).isEqualTo(1);
            assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
        }
    }

    @Test
    void twoExpiryWorkersRestoreEachOrderOnlyOnce() throws Exception {
        long blockedVoucher = seckillVoucher(1);
        String blockedOrder = orderIdOf(seckillResult(blockedVoucher, 80_000, "blocked-expiry"));
        drainUntilOrders(blockedVoucher, 1);
        sql.update("UPDATE commerce_order SET pay_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE WHERE id=?", blockedOrder);
        redis.opsForValue().set(keyspace.fence(blockedVoucher), "inventory-maintenance");
        long voucherId = seckillVoucher(10);
        for (int buyer = 0; buyer < 10; buyer++) seckillResult(voucherId, 80_001 + buyer, "expiry-" + buyer);
        drainUntilOrders(voucherId, 10);
        sql.update("UPDATE commerce_order SET pay_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE voucher_id=?", voucherId);

        List<String> expired = concurrently(2, ignored -> String.valueOf(orders.expireDueOrders()));

        assertThat(expired.stream().mapToInt(Integer::parseInt).sum() + orders.expireDueOrders()).isEqualTo(10);
        assertThat(databaseStock(voucherId)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM commerce_order_recovery WHERE voucher_id=? AND source_type='STATE_TRANSITION' AND phase='RESTORED'",
                voucherId)).isEqualTo(10);
        assertThat(consistency.snapshot(voucherId)).containsEntry("consistent", true);
        assertThat(orders.order(Long.parseLong(blockedOrder), 80_000, false, false).state()).isEqualTo("unpaid");
        redis.delete(keyspace.fence(blockedVoucher));
        assertThat(orders.expireDueOrders()).isEqualTo(1);
    }

    @Test
    void soldOutFastRejectionPreservesPreparedRetriesAndBroadcastsRecovery() throws Exception {
        long voucherId = seckillVoucher(1);
        long owner = 91_001;
        String orderId = orderIdOf(seckillResult(voucherId, owner, "sold-out-owner"));
        assertThat(seckill(voucherId, 91_002, "sold-out-probe")).isEqualTo("SECKILL_STOCK_NOT_ENOUGH");
        assertThat(readCache.isSoldOut(voucherId)).isTrue();
        assertThat(seckill(voucherId, 91_003, "sold-out-fast")).isEqualTo("SECKILL_STOCK_NOT_ENOUGH");
        assertThat(redis.hasKey(keyspace.globalIdempotency(91_003, "sold-out-fast"))).isFalse();
        // 售罄快速拒绝不写账本，售罄洪峰不会转成 MySQL 写入。
        assertThat(count("SELECT COUNT(*) FROM commerce_admission_ledger WHERE user_id=?", 91_003)).isZero();
        sql.update("UPDATE commerce_admission_ledger SET status='PREPARED',accepted_at=NULL WHERE order_id=?", orderId);
        assertThat(seckillResult(voucherId, owner, "sold-out-owner").orderId()).isEqualTo(orderId);
        drainUntilOrders(voucherId, 1);
        var replica = new CommerceReadCache(redis, keyspace, JsonMapper.builderWithJackson2Defaults().build());
        var recovered = new CountDownLatch(1);
        var listener = replica.inventoryCacheListener(redisFactory);
        listener.addMessageListener((message, pattern) -> recovered.countDown(), new ChannelTopic(CommerceReadCache.INVENTORY_CHANNEL));
        listener.afterPropertiesSet();
        listener.start();
        try {
            replica.markSoldOut(voucherId);
            orders.transition(Long.parseLong(orderId), owner, "cancel", false, false);
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
            awaitSoldOutClear(voucherId);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (replica.isSoldOut(voucherId) && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(replica.isSoldOut(voucherId)).isFalse();
            assertThat(seckill(voucherId, 91_003, "after-stock-return")).isEqualTo("COMMERCE_ORDER_ACCEPTED");
        } finally {
            listener.destroy();
        }
    }

    @Test
    void voucherListUpdatesInvalidateTheVersionAndLateSnapshotsCannotRefillIt() throws Exception {
        long voucherId = seckillVoucher(2);
        sql.update("UPDATE commerce_voucher SET shop_id=? WHERE id=?", voucherId, voucherId);
        var before = vouchers.list(voucherId);
        assertThat(readCache.vouchers(voucherId, () -> { throw new AssertionError("cache miss"); }).get(0).title())
                .isEqualTo(before.get(0).title());
        new TransactionTemplate(new DataSourceTransactionManager(pool)).executeWithoutResult(status ->
                vouchers.update(voucherId, java.util.Map.of("title", "updated title"), 1L, true));
        assertThat(vouchers.list(voucherId).get(0).title()).isEqualTo("updated title");
        readCache.invalidateVouchers(voucherId);
        var loaded = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var stale = executor.submit(() -> readCache.vouchers(voucherId, () -> {
                loaded.countDown();
                try { assertThat(resume.await(3, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return before;
            }));
            assertThat(loaded.await(3, TimeUnit.SECONDS)).isTrue();
            readCache.invalidateVouchers(voucherId);
            resume.countDown();
            stale.get(3, TimeUnit.SECONDS);
            assertThat(vouchers.list(voucherId).get(0).title()).isEqualTo("updated title");
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitSoldOutClear(long voucherId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (readCache.isSoldOut(voucherId) && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(readCache.isSoldOut(voucherId)).isFalse();
    }

    private static HikariDataSource pool() {
        var config = new HikariConfig();
        config.setPoolName("seckill-it");
        config.setJdbcUrl(System.getenv("CITYCOPILOT_SECKILL_TEST_DB_URL"));
        config.setUsername(System.getenv().getOrDefault("CITYCOPILOT_SECKILL_TEST_DB_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("CITYCOPILOT_SECKILL_TEST_DB_PASSWORD", ""));
        config.setMaximumPoolSize(16);
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        return new HikariDataSource(config);
    }

    @Test
    void v10OrdersRemainReadableAndOldCancelledBuyersRequireEvidenceRepair() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "cc_order_upgrade_" + suffix + "_test";
        String generation = "upgrade-" + suffix.substring(0, 16);
        String original = pool.getJdbcUrl();
        int databaseStart = original.indexOf('/', "jdbc:mysql://".length()) + 1;
        int optionsStart = original.indexOf('?', databaseStart);
        String url = original.substring(0, databaseStart) + database
                + (optionsStart < 0 ? "" : original.substring(optionsStart));
        sql.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(pool.getUsername());
        config.setPassword(pool.getPassword());
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        try (var migratedPool = new HikariDataSource(config)) {
            Path migrations = Path.of("../..").toAbsolutePath().normalize().resolve("deploy/mysql/commerce");
            org.flywaydb.core.Flyway.configure().dataSource(migratedPool).locations("filesystem:" + migrations)
                    .target("10").load().migrate();
            var migratedSql = new JdbcTemplate(migratedPool);
            migratedSql.update("INSERT INTO commerce_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES (990001,1,'legacy',100,200,1,1)");
            migratedSql.update("""
                    INSERT INTO commerce_seckill_voucher(voucher_id,stock,begin_time,end_time)
                    VALUES (990001,1,UTC_TIMESTAMP(3)-INTERVAL 1 DAY,UTC_TIMESTAMP(3)+INTERVAL 1 DAY)
                    """);
            migratedSql.update("""
                    INSERT INTO commerce_order(id,user_id,voucher_id,shop_id,shop_name_snapshot,voucher_title_snapshot,
                        pay_value_snapshot,actual_value_snapshot,status,idempotency_key,pay_time)
                    VALUES (990101,990011,990001,1,'legacy shop','legacy',100,200,4,'legacy-cancel',NULL),
                           (990102,990012,990001,1,'legacy shop','legacy',100,200,2,'legacy-paid',UTC_TIMESTAMP(3))
                    """);
            migratedSql.update("""
                    INSERT INTO commerce_reservation(order_id,user_id,voucher_id,status)
                    VALUES (990101,990011,990001,'ORDER_CREATED'),(990102,990012,990001,'ORDER_CREATED')
                    """);
            migratedSql.update("""
                    INSERT INTO commerce_admission_ledger(order_id,user_id,idempotency_key,voucher_id,request_fingerprint,
                        routing_generation,physical_shard_count,status,accepted_at,terminal_at)
                    SELECT id,user_id,idempotency_key,voucher_id,CONCAT('seckill:v1:',voucher_id),?,1,
                        'ORDER_CREATED',create_time,create_time FROM commerce_order
                    """, generation);
            migratedSql.update("UPDATE commerce_id_segment SET next_value=990102 WHERE namespace='order'");
            org.flywaydb.core.Flyway.configure().dataSource(migratedPool).locations("filesystem:" + migrations)
                    .load().migrate();
            assertThat(migratedSql.queryForObject("SELECT TIMESTAMPDIFF(SECOND,create_time,pay_deadline) FROM commerce_order WHERE id=990101", Long.class))
                    .isEqualTo(900);
            assertThat(migratedSql.queryForObject("SELECT status FROM commerce_reservation WHERE order_id=990101", String.class))
                    .isEqualTo("CANCELLED");
            var migratedKeys = new CommerceRedisKeyspace(1, generation);
            var migratedTransactions = new TransactionTemplate(new DataSourceTransactionManager(migratedPool));
            var json = JsonMapper.builderWithJackson2Defaults().build();
            var metrics = new SimpleMeterRegistry();
            var migratedCache = new CommerceReadCache(redis, migratedKeys, json);
            var ids = new CommerceOrderIdGenerator(migratedSql, migratedTransactions, 1024);
            var durable = new CommerceAdmissionDurabilityService(migratedSql, ids, migratedKeys, metrics,
                    100, 0, 30_000, 5_000, 20, 50_000, 300_000, 1, "migration-it", migratedCache);
            var recovery = new CommerceOrderRecoveryStore(migratedSql);
            var migratedAdmissions = new CommerceOrderAdmissionService(migratedSql, migratedTransactions, ids, durable, replicatedRedis, migratedKeys, migratedCache);
            var migratedOrders = new CommerceOrderService(migratedSql, redis, migratedTransactions, json, durable,
                    migratedKeys, recovery, mock(PlaceAuthorityClient.class));
            var migratedPayments = new CommercePaymentService(migratedSql, migratedTransactions, migratedOrders,
                    new CommerceJobLease(migratedSql, migratedTransactions), "integration-payment-signing-secret", true);
            assertThat(migratedOrders.order(990101, 990011, false, false).state()).isEqualTo("cancelled");
            assertThat(migratedOrders.order(990102, 990012, false, false).state()).isEqualTo("paid");
            assertThat(migratedPayments.review("legacy:990102").paymentState()).isEqualTo("PAID");
            assertThat(migratedAdmissions.seckill(990001, 990011, "legacy-cancel").orderId()).isEqualTo("990101");
            redis.opsForValue().set(migratedKeys.stock(990001), "1");
            redis.opsForSet().add(migratedKeys.buyers(990001), "990011", "990012");
            new CommerceRedisCampaignMetadata(migratedSql, redis, migratedKeys, true, 300_000).refreshAfterCommit(990001);
            var migratedWorker = new CommerceOrderStreamWorker(migratedSql, redis, migratedTransactions, json, durable,
                    migratedKeys, migratedOrders, migratedAdmissions, recovery, "migration-it", true, 100, 10);
            migratedWorker.initializeStream();
            assertThatThrownBy(() -> migratedAdmissions.seckill(990001, 990011, "before-repair"))
                    .isInstanceOfSatisfying(CommerceApiException.class,
                            rejected -> assertThat(rejected.code()).isEqualTo("SECKILL_DUPLICATE_ORDER"));
            var migratedConsistency = new CommerceConsistencyService(migratedSql, redis, json, migratedKeys, replicatedRedis);
            var evidence = migratedConsistency.snapshot(990001);
            assertThat(evidence.get("unexpectedRedisBuyers")).isEqualTo(List.of("990011"));
            assertThat(migratedConsistency.repair(990001, (String) evidence.get("evidenceHash")).verified()).isTrue();
            assertThat(migratedAdmissions.seckill(990001, 990011, "after-repair").code()).isEqualTo("COMMERCE_ORDER_ACCEPTED");
            migratedWorker.consumeOrders();
            migratedWorker.consumeOrders();
            assertThat(migratedSql.queryForObject("SELECT COUNT(*) FROM commerce_order", Long.class)).isEqualTo(3);
            assertThat(migratedConsistency.snapshot(990001)).containsEntry("consistent", true);
            assertThat(migratedAdmissions.seckill(990001, 990011, "legacy-cancel").orderId()).isEqualTo("990101");
            metrics.close();
        } finally {
            sql.execute("DROP DATABASE " + database);
            Set<String> migratedKeys = redis.keys("commerce:*:" + generation + ":*");
            if (!migratedKeys.isEmpty()) redis.delete(migratedKeys);
        }
    }

    /** 直接写入一张进行中的秒杀券，再走生产代码初始化 Redis 库存和活动元数据。 */
    private static long seckillVoucher(int stock) {
        long voucherId = NEXT_VOUCHER.incrementAndGet();
        sql.update("INSERT INTO commerce_voucher(id,shop_id,title,pay_value,actual_value,type,status) VALUES (?,?,?,?,?,1,1)",
                voucherId, 1L, "IT voucher " + voucherId, 100, 200);
        sql.update("""
                INSERT INTO commerce_seckill_voucher(voucher_id,stock,begin_time,end_time)
                VALUES (?,?,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(3)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(3)))
                """, voucherId, stock);
        inventory.initializeNewVoucher(voucherId, stock);
        metadata.refreshAfterCommit(voucherId);
        return voucherId;
    }

    private static AdmissionAccepted seckillResult(long voucherId, long userId, String key) {
        return admissions.seckill(voucherId, userId, key);
    }

    /** 返回受理码或业务拒绝码，便于统计并发结果。 */
    private static String seckill(long voucherId, long userId, String key) {
        try {
            return seckillResult(voucherId, userId, key).code();
        } catch (CommerceApiException rejected) {
            return rejected.code();
        }
    }

    private static String orderIdOf(AdmissionAccepted accepted) {
        return accepted.orderId();
    }

    private interface Attempt {
        String run(int index) throws Exception;
    }

    private static List<String> concurrently(int count, Attempt attempt) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            var ready = new CountDownLatch(count);
            var start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return attempt.run(index);
                }));
            }
            ready.await();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** 手动驱动 Worker 直到订单落库，再多跑一轮确保 Redis 终态清理完成。 */
    private static void drainUntilOrders(long voucherId, int expected) {
        for (int round = 0; round < 50
                && count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId) < expected; round++) {
            worker.consumeOrders();
        }
        worker.consumeOrders();
        assertThat(count("SELECT COUNT(*) FROM commerce_order WHERE voucher_id=?", voucherId)).isEqualTo(expected);
        Set<String> inflight = redis.opsForSet().members(keyspace.inflight(voucherId));
        assertThat(inflight).isEmpty();
    }

    /** 依次为连续用户受理同一张券，返回各自订单号（顺序即 Stream 写入顺序）。 */
    private static List<Long> admitUsers(long voucherId, long firstUserId, int users) {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            orderIds.add(Long.parseLong(orderIdOf(seckillResult(voucherId, firstUserId + i, "batch-" + voucherId + "-" + i))));
        }
        return orderIds;
    }

    /** 该券仍留在分片 Stream 中的订单消息，按写入顺序。 */
    private static List<MapRecord<String, Object, Object>> streamRecords(long voucherId) {
        return redis.opsForStream().range(keyspace.stream(voucherId), Range.unbounded()).stream()
                .filter(record -> String.valueOf(voucherId).equals(String.valueOf(record.getValue().get("voucherId"))))
                .toList();
    }

    private static long field(MapRecord<String, Object, Object> record, String name) {
        return Long.parseLong(String.valueOf(record.getValue().get(name)));
    }

    private static long orderCreatedEvents(long voucherId) {
        return count("""
                SELECT COUNT(*) FROM commerce_outbox_event e
                JOIN commerce_order o ON e.aggregate_id=CAST(o.id AS CHAR)
                WHERE o.voucher_id=? AND e.event_type='commerce.order.created'
                """, voucherId);
    }

    private static int databaseStock(long voucherId) {
        return sql.queryForObject("SELECT stock FROM commerce_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId);
    }

    private static long count(String query, Object argument) {
        return sql.queryForObject(query, Long.class, argument);
    }
}
