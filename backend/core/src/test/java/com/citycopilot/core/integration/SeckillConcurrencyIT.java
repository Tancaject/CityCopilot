package com.citycopilot.core.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeckillConcurrencyIT {
    private static final long VOUCHER_ID = 99_001L;
    private static final int STOCK = 50;
    private static final String MYSQL_USERNAME = "test";
    private static final String MYSQL_PASSWORD = "test";

    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>(DockerImageName.parse("mysql:8.4.10"))
            .withEnv("MYSQL_DATABASE", "citycopilot")
            .withEnv("MYSQL_USER", MYSQL_USERNAME)
            .withEnv("MYSQL_PASSWORD", MYSQL_PASSWORD)
            .withEnv("MYSQL_ROOT_PASSWORD", "root-test-password")
            .withExposedPorts(3306)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUp() {
        redisConnectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @Test
    @Order(1)
    void importsV2Schema() throws Exception {
        try (Connection connection = connection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource(findSchemaScript())
            );
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT COUNT(*) FROM information_schema.tables "
                                 + "WHERE table_schema='citycopilot' "
                                 + "AND table_name IN ('shop','coupon','coupon_order','note','user_account',"
                                 + "'seckill_reservation','outbox_event','processed_event','event_projection_version')"
                 )) {
                assertTrue(result.next());
                assertEquals(9, result.getInt(1));
            }
        }
    }

    @Test
    @Order(2)
    void twoHundredConcurrentRequestsDoNotOversellOrDuplicateUsers() throws Exception {
        prepareInventory();
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill.lua"));
        script.setResultType(Long.class);
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong orderIds = new AtomicLong(1_000_000L);
        List<Future<Long>> calls = new ArrayList<>();

        for (int request = 0; request < 200; request++) {
            long userId = request % 100;
            calls.add(executor.submit(() -> {
                start.await();
                return redisTemplate.execute(
                        script,
                        Collections.emptyList(),
                        String.valueOf(VOUCHER_ID),
                        String.valueOf(userId),
                        String.valueOf(orderIds.incrementAndGet())
                );
            }));
        }
        start.countDown();
        int successful = 0;
        for (Future<Long> call : calls) {
            if (Long.valueOf(0L).equals(call.get(10, TimeUnit.SECONDS))) {
                successful++;
            }
        }
        executor.shutdownNow();

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range("stream.coupon-orders", Range.unbounded());
        assertEquals(STOCK, successful);
        assertEquals(STOCK, records == null ? 0 : records.size());
        persistStreamRecords(records == null ? List.of() : records);

        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            assertEquals(STOCK, scalar(statement, "SELECT COUNT(*) FROM coupon_order WHERE voucher_id=" + VOUCHER_ID));
            assertEquals(STOCK, scalar(statement, "SELECT COUNT(DISTINCT user_id) FROM coupon_order WHERE voucher_id=" + VOUCHER_ID));
            assertEquals(0, scalar(statement, "SELECT stock FROM seckill_coupon WHERE voucher_id=" + VOUCHER_ID));
        }
        assertEquals("0", redisTemplate.opsForValue().get("coupon:stock:" + VOUCHER_ID));
    }

    @Test
    @Order(3)
    void reservationConfirmAndReleaseAreIdempotentAcrossTenReplays() {
        DefaultRedisScript<Long> seckill = script("lua/seckill.lua");
        DefaultRedisScript<Long> confirm = script("lua/confirm_reservation.lua");
        DefaultRedisScript<Long> release = script("lua/release_reservation.lua");

        long confirmedOrderId = 2_000_001L;
        redisTemplate.opsForValue().set("coupon:stock:" + VOUCHER_ID, "1");
        redisTemplate.delete("coupon:buyer:" + VOUCHER_ID);
        assertEquals(0L, redisTemplate.execute(
                seckill, List.of(), String.valueOf(VOUCHER_ID), "7001", String.valueOf(confirmedOrderId)
        ));
        for (int replay = 0; replay < 10; replay++) {
            assertEquals(1L, redisTemplate.execute(
                    confirm, List.of("coupon:reservation:" + confirmedOrderId)
            ));
        }
        assertEquals("0", redisTemplate.opsForValue().get("coupon:stock:" + VOUCHER_ID));
        assertEquals("ORDER_CREATED", redisTemplate.opsForHash()
                .get("coupon:reservation:" + confirmedOrderId, "status"));

        long releasedOrderId = 2_000_002L;
        redisTemplate.opsForValue().set("coupon:stock:" + VOUCHER_ID, "1");
        redisTemplate.delete("coupon:buyer:" + VOUCHER_ID);
        assertEquals(0L, redisTemplate.execute(
                seckill, List.of(), String.valueOf(VOUCHER_ID), "7002", String.valueOf(releasedOrderId)
        ));
        for (int replay = 0; replay < 10; replay++) {
            assertEquals(1L, redisTemplate.execute(
                    release,
                    List.of(
                            "coupon:reservation:" + releasedOrderId,
                            "coupon:stock:" + VOUCHER_ID,
                            "coupon:buyer:" + VOUCHER_ID
                    ),
                    "7002"
            ));
        }
        assertEquals("1", redisTemplate.opsForValue().get("coupon:stock:" + VOUCHER_ID));
        assertEquals(Boolean.FALSE, redisTemplate.opsForSet()
                .isMember("coupon:buyer:" + VOUCHER_ID, "7002"));
        assertEquals("RELEASED", redisTemplate.opsForHash()
                .get("coupon:reservation:" + releasedOrderId, "status"));
    }

    @Test
    @Order(4)
    void databaseUniqueConstraintRejectsTwoActiveOrdersForOneUserAndVoucher() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM coupon_order WHERE voucher_id=" + VOUCHER_ID);
            statement.executeUpdate(
                    "INSERT INTO coupon_order(id,user_id,voucher_id,status,version,create_time,update_time) "
                            + "VALUES(3000001,8001," + VOUCHER_ID + ",1,0,NOW(),NOW())"
            );
            assertThrows(Exception.class, () -> statement.executeUpdate(
                    "INSERT INTO coupon_order(id,user_id,voucher_id,status,version,create_time,update_time) "
                            + "VALUES(3000002,8001," + VOUCHER_ID + ",1,0,NOW(),NOW())"
            ));
            statement.executeUpdate("UPDATE coupon_order SET status=4 WHERE id=3000001");
            assertEquals(1, statement.executeUpdate(
                    "INSERT INTO coupon_order(id,user_id,voucher_id,status,version,create_time,update_time) "
                            + "VALUES(3000002,8001," + VOUCHER_ID + ",1,0,NOW(),NOW())"
            ));
        }
    }

    private static void prepareInventory() throws Exception {
        redisTemplate.delete("stream.coupon-orders");
        redisTemplate.delete("coupon:stock:" + VOUCHER_ID);
        redisTemplate.opsForValue().set("coupon:stock:" + VOUCHER_ID, String.valueOf(STOCK));
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM coupon_order WHERE voucher_id=" + VOUCHER_ID);
            statement.executeUpdate("DELETE FROM seckill_coupon WHERE voucher_id=" + VOUCHER_ID);
            statement.executeUpdate("DELETE FROM coupon WHERE id=" + VOUCHER_ID);
            statement.executeUpdate(
                    "INSERT INTO coupon(id,shop_id,title,pay_value,actual_value,type,status,create_time,update_time) "
                            + "VALUES(" + VOUCHER_ID + ",1,'IT coupon',100,200,1,1,NOW(),NOW())"
            );
            statement.executeUpdate(
                    "INSERT INTO seckill_coupon(voucher_id,stock,begin_time,end_time,create_time,update_time) "
                            + "VALUES(" + VOUCHER_ID + "," + STOCK + ",NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY),NOW(),NOW())"
            );
        }
    }

    private static void persistStreamRecords(List<MapRecord<String, Object, Object>> records) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (var decrement = connection.prepareStatement(
                    "UPDATE seckill_coupon SET stock=stock-1 WHERE voucher_id=? AND stock>0"
            ); var insert = connection.prepareStatement(
                    "INSERT INTO coupon_order(id,user_id,voucher_id,status,create_time,update_time) VALUES(?,?,?,?,?,?)"
            )) {
                for (MapRecord<String, Object, Object> record : records) {
                    Map<Object, Object> values = record.getValue();
                    decrement.setLong(1, VOUCHER_ID);
                    assertEquals(1, decrement.executeUpdate());
                    insert.setLong(1, Long.parseLong(String.valueOf(values.get("id"))));
                    insert.setLong(2, Long.parseLong(String.valueOf(values.get("userId"))));
                    insert.setLong(3, VOUCHER_ID);
                    insert.setInt(4, 1);
                    insert.setObject(5, LocalDateTime.now());
                    insert.setObject(6, LocalDateTime.now());
                    insert.executeUpdate();
                }
            }
            connection.commit();
        }
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Path findSchemaScript() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("citycopilot-v2.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("citycopilot-v2.sql was not found above the test working directory");
    }

    private static Connection connection() throws Exception {
        String jdbcUrl = "jdbc:mysql://%s:%d/citycopilot"
                .formatted(MYSQL.getHost(), MYSQL.getMappedPort(3306))
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8";
        return DriverManager.getConnection(jdbcUrl, MYSQL_USERNAME, MYSQL_PASSWORD);
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
