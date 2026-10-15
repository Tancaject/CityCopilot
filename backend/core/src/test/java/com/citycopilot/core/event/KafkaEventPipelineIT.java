package com.citycopilot.core.event;

import com.citycopilot.core.config.KafkaEventConfig;
import com.citycopilot.core.config.KafkaEventProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在真实 3 节点 Kafka 与一次性 MySQL 库上验证事件消费链路：去重、坏消息进死信不阻塞分区、暂时性故障重试后成功。
 * 每次运行使用独立的主题和库，不读写日常数据。
 */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_KAFKA_TEST_BOOTSTRAP", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_MIGRATION_TEST_URL", matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")
class KafkaEventPipelineIT {
    private static final String BOOTSTRAP = System.getenv("CITYCOPILOT_KAFKA_TEST_BOOTSTRAP");
    private static final String SERVER = System.getenv("CITYCOPILOT_MIGRATION_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_PASSWORD", "");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL&forceConnectionTimeZoneToSession=true";
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String DATABASE = "cc_kafka_" + RUN;
    private static final String TOPIC = "it-commerce-events-" + RUN;
    private static final String OPS_TOPIC = "it-ops-events-" + RUN;
    private static final String GROUP = "it-event-audit-" + RUN;
    private static final String SALES_GROUP = "it-shop-sales-" + RUN;

    private static HikariDataSource pool;
    private static JdbcTemplate sql;
    private static ConfigurableApplicationContext context;
    private static KafkaProducer<String, byte[]> producer;

    @BeforeAll
    static void setup() throws Exception {
        try (var admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(SERVER + DATABASE + OPTIONS);
        config.setUsername(USER);
        config.setPassword(PASSWORD);
        pool = new HikariDataSource(config);
        sql = new JdbcTemplate(pool);
        Flyway.configure().dataSource(pool).locations("filesystem:" + Path.of("../../deploy/mysql/core").toAbsolutePath().normalize())
                .cleanDisabled(true).load().migrate();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            // 本地是 3 节点集群；CI 只有单节点，副本数按实际节点数取，不超过 3。
            short replicas = (short) Math.min(3, admin.describeCluster().nodes().get().size());
            String minInsync = String.valueOf(Math.max(1, replicas - 1));
            admin.createTopics(List.of(TOPIC, OPS_TOPIC, TOPIC + ".DLT", OPS_TOPIC + ".DLT").stream()
                    .map(name -> new NewTopic(name, 6, replicas).configs(Map.of("min.insync.replicas", minInsync)))
                    .toList()).all().get();
        }
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        context = new SpringApplicationBuilder(Pipeline.class)
                .web(WebApplicationType.NONE)
                // 命令行参数优先级高于 classpath 上的 application.yaml，保证只用本次的一次性主题和消费组。
                .run("--spring.kafka.bootstrap-servers=" + BOOTSTRAP,
                        "--citycopilot.kafka.enabled=true",
                        "--citycopilot.kafka.commerce-topic=" + TOPIC,
                        "--citycopilot.kafka.ops-topic=" + OPS_TOPIC,
                        "--citycopilot.kafka.audit-consumer-group=" + GROUP,
                        "--citycopilot.kafka.shop-sales-consumer-group=" + SALES_GROUP);
    }

    @AfterAll
    static void close() throws Exception {
        if (context != null) context.close();
        if (producer != null) producer.close();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.deleteTopics(List.of(TOPIC, OPS_TOPIC, TOPIC + ".DLT", OPS_TOPIC + ".DLT")).all().get();
        }
        if (pool != null) pool.close();
        try (var admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void duplicateDeliveryLeavesTheInboxUnchanged() throws Exception {
        String eventId = UUID.randomUUID().toString();
        byte[] event = envelope(eventId, "order-1", 1);

        send("order-1", event);
        await(() -> "PROCESSED".equals(inboxStatus(eventId)));
        String recorded = sql.queryForObject(
                "SELECT CONCAT(status,':',processed_at) FROM processed_event WHERE event_id=?", String.class, eventId);
        send("order-1", event);
        await(() -> counter("duplicate") >= 1);

        assertThat(sql.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(sql.queryForObject("SELECT CONCAT(status,':',processed_at) FROM processed_event WHERE event_id=?",
                String.class, eventId)).isEqualTo(recorded);
    }

    @Test
    void malformedEventGoesToDeadLetterWithoutBlockingItsPartition() throws Exception {
        byte[] malformed = "{\"eventId\":\"broken\"}".getBytes(StandardCharsets.UTF_8);
        String nextEvent = UUID.randomUUID().toString();

        // 同一个 key 落在同一分区：坏消息之后的事件仍要被处理。
        send("order-2", malformed);
        send("order-2", envelope(nextEvent, "order-2", 1));

        await(() -> "PROCESSED".equals(inboxStatus(nextEvent)));
        assertThat(deadLetterValues()).contains(new String(malformed, StandardCharsets.UTF_8));
    }

    @Test
    void transientStorageFailureIsRetriedInsteadOfDeadLettered() throws Exception {
        String eventId = UUID.randomUUID().toString();
        sql.execute("RENAME TABLE processed_event TO processed_event_offline");
        try {
            send("order-3", envelope(eventId, "order-3", 1));
            await(() -> counter("failure") >= 1);
        } finally {
            sql.execute("RENAME TABLE processed_event_offline TO processed_event");
        }

        await(() -> "PROCESSED".equals(inboxStatus(eventId)));
        assertThat(deadLetterValues()).noneMatch(value -> value.contains(eventId));
    }

    @Test
    void paidOrdersCountTowardsShopSalesOnceAndRefundsTakeThemBack() throws Exception {
        long shop = shop();
        send("71", orderEvent("created", 71, shop, 1, 0));
        byte[] paid = orderEvent("pay", 71, shop, 2, 2);
        send("71", paid);
        await(() -> sold(shop) == 1);
        send("71", paid);
        await(() -> salesCounter("duplicate") >= 1);
        assertThat(sold(shop)).isEqualTo(1);

        send("71", orderEvent("refund", 71, shop, 6, 6));
        await(() -> sold(shop) == 0);
    }

    @Test
    void lateOrCancelledOrdersNeverCountTowardsShopSales() throws Exception {
        long shop = shop();
        // 退款先到：迟到的支付版本更低，必须被丢弃。
        send("72", orderEvent("refund", 72, shop, 6, 6));
        await(() -> projectedStatus(72) == 6);
        String latePay = UUID.randomUUID().toString();
        send("72", orderEvent(latePay, "pay", 72, shop, 2, 2));
        await(() -> "STALE".equals(sql.queryForObject(
                "SELECT MAX(status) FROM processed_event WHERE event_id=?", String.class, latePay)));
        // 未支付就取消。
        send("73", orderEvent("created", 73, shop, 1, 0));
        send("73", orderEvent("cancel", 73, shop, 4, 4));
        await(() -> projectedStatus(73) == 4);

        assertThat(sold(shop)).isZero();
    }

    private static long shop() {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        sql.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO shop(name,type_id,images,address,sold,comments,score)
                    VALUES ('it-shop',1,'it.png','it',0,0,50)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static int sold(long shop) {
        return sql.queryForObject("SELECT sold FROM shop WHERE id=?", Integer.class, shop);
    }

    private static int projectedStatus(long orderId) {
        List<Integer> rows = sql.queryForList("SELECT status FROM shop_order_sales WHERE order_id=?", Integer.class, orderId);
        return rows.isEmpty() ? -1 : rows.get(0);
    }

    private static double salesCounter(String outcome) {
        var meter = context.getBean(MeterRegistry.class).find("citycopilot.shop_sales.apply").tag("outcome", outcome).counter();
        return meter == null ? 0 : meter.count();
    }

    private static byte[] orderEvent(String action, long orderId, long shopId, int status, long version) {
        return orderEvent(UUID.randomUUID().toString(), action, orderId, shopId, status, version);
    }

    /** 与 Commerce 写入 Outbox 的订单事件同形：aggregateVersion 取订单状态码，载荷带 shopId。 */
    private static byte[] orderEvent(String eventId, String action, long orderId, long shopId, int status, long version) {
        return ("""
                {"eventId":"%s","eventType":"commerce.order.%s","schemaVersion":2,
                 "aggregateType":"commerce_order","aggregateId":"%d","aggregateVersion":%d,
                 "producer":"it","occurredAt":"2026-10-15T00:00:00Z","correlationId":"%d",
                 "payload":{"orderId":%d,"userId":1,"status":%d,"shopId":%d,"voucherId":1}}
                """.formatted(eventId, action, orderId, version, orderId, orderId, status, shopId))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void send(String key, byte[] value) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, key, value)).get();
    }

    private static byte[] envelope(String eventId, String aggregateId, long version) {
        return ("""
                {"eventId":"%s","eventType":"commerce.order.created","schemaVersion":1,
                 "aggregateType":"commerce_order","aggregateId":"%s","aggregateVersion":%d,
                 "producer":"it","occurredAt":"2026-10-15T00:00:00Z","correlationId":"%s","payload":{}}
                """.formatted(eventId, aggregateId, version, aggregateId)).getBytes(StandardCharsets.UTF_8);
    }

    private static String inboxStatus(String eventId) {
        List<String> rows = sql.queryForList("SELECT status FROM processed_event WHERE event_id=?", String.class, eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static double counter(String outcome) {
        var meter = context.getBean(MeterRegistry.class).find("citycopilot.events.consume").tag("outcome", outcome).counter();
        return meter == null ? 0 : meter.count();
    }

    private static List<String> deadLetterValues() {
        try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ConsumerConfig.GROUP_ID_CONFIG, "it-dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            var values = new java.util.ArrayList<String>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500))
                        .forEach(record -> values.add(new String(record.value(), StandardCharsets.UTF_8)));
                if (!values.isEmpty() && !consumer.assignment().isEmpty()) break;
            }
            return values;
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for the Kafka consumer");
            Thread.sleep(200);
        }
    }

    @Configuration
    @EnableKafka
    @EnableTransactionManagement
    @EnableConfigurationProperties(KafkaEventProperties.class)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    @Import({KafkaEventConfig.class, KafkaEventAuditConsumer.class, ShopSalesConsumer.class, InboxRepository.class})
    static class Pipeline {
        @Bean
        JdbcTemplate jdbcTemplate() {
            return sql;
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(pool);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
