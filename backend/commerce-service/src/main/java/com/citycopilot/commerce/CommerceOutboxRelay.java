package com.citycopilot.commerce;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 将 MySQL Outbox 中已提交的领域事件可靠转发到 Kafka。
 *
 * <p>多个 Relay 可并发运行：数据库条件更新负责抢占，{@code claim_token + fencing_token}
 * 防止过期 Worker 覆盖新所有者状态。发布和标记成功不是同一事务，因此下游仍须按 eventId 幂等。
 * Kafka 事务只覆盖 Kafka 内部，覆盖不到“MySQL 提交 + 发送”，所以这里是至少一次投递 + 消费端 Inbox。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.commerce",
        name = "outbox-relay-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CommerceOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(CommerceOutboxRelay.class);
    /** 读取、抢占并更新 Outbox 行。 */
    private final JdbcTemplate jdbc;
    /** 记录积压、发布结果和耗时。 */
    private final MeterRegistry metrics;
    /** 关闭发布时为 null，调度任务保持空操作，Outbox 数据原样保留。 */
    private final KafkaTemplate<String, String> kafka;
    /** 单条发送等待 broker 确认的上限；超时按失败退避重试，下游按 eventId 去重。 */
    private static final long SEND_TIMEOUT_SECONDS = 15;

    public CommerceOutboxRelay(
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            KafkaTemplate<String, String> kafka,
            @Value("${citycopilot.commerce.kafka-enabled:true}") boolean enabled
    ) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.kafka = enabled ? kafka : null;
        Gauge.builder("citycopilot.commerce.outbox.unpublished", jdbc, ignored -> unpublished())
                .register(metrics);
        Gauge.builder("citycopilot.commerce.outbox.oldest_ready_age_seconds", jdbc, ignored -> oldestReadyAgeSeconds())
                .register(metrics);
    }

    /** 周期扫描可发布或租约已过期的 Outbox 行，单条失败不阻断后续事件。 */
    @Scheduled(fixedDelayString = "${citycopilot.commerce.outbox-relay-ms:1000}")
    void relay() {
        if (kafka == null) return;
        for (String eventId : relayCandidates()) {
            try {
                publish(eventId);
            } catch (RuntimeException failure) {
                log.warn("Unable to relay Commerce outbox event {}", eventId, failure);
            }
        }
    }

    /**
     * 按创建时间选取一小批可重试事件；过期 CLAIMED 行也允许被新 Worker 接管。
     *
     * @return 候选 eventId，最终所有权仍由 {@link #claim(String)} 决定
     */
    List<String> relayCandidates() {
        return jdbc.queryForList("""
                SELECT event_id FROM commerce_outbox_event
                 WHERE (status IN ('READY','FAILED')
                       AND next_attempt_at <= CURRENT_TIMESTAMP(3))
                   OR (status='CLAIMED'
                       AND claimed_until < CURRENT_TIMESTAMP(3))
                ORDER BY created_at
                LIMIT 50
                """, String.class);
    }

    /**
     * 抢占并发布一个 Outbox 事件，再以同一 fencing token 标记结果。
     *
     * @param eventId Outbox 事件 ID，放在消息头里供下游去重
     */
    void publish(String eventId) {
        OutboxClaim claim = claim(eventId);
        if (claim == null) return;
        Timer.Sample sample = Timer.start(metrics);
        try {
            // acks=all + min.insync.replicas=2：拿到确认才标记已发布，确认前失败按退避重试。
            kafka.send(claimedRecord(claim)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // 发送成功后仍用 claim token + fencing token 条件更新；过期所有者不能篡改新租约状态。
            if (markPublished(claim)) {
                count("published");
            } else {
                count("lost_claim");
                log.warn("Commerce outbox ownership changed after publishing {}", eventId);
            }
        } catch (Exception error) {
            // Kafka 的真实原因（如 NotEnoughReplicasException）包在 ExecutionException/KafkaProducerException 里，记最内层的。
            if (markFailed(claim, rootCause(error))) {
                count("failed");
            } else {
                count("lost_claim");
                log.warn("Commerce outbox ownership changed while handling failure for {}", eventId);
            }
        } finally {
            sample.stop(Timer.builder("citycopilot.commerce.events.publish.duration")
                    .register(metrics));
        }
    }

    /**
     * 重投也读取同一原文；归档不能改变 eventId、正文编码或内容摘要。
     *
     * <p>消息 key 用聚合 ID，同一订单或预约的事件落在同一分区。消费端仍按 aggregateVersion 判新旧，
     * 不依赖分区顺序：多个 Relay 的发送与重试顺序本来就无法保证。</p>
     */
    ProducerRecord<String, String> claimedRecord(OutboxClaim claim) throws Exception {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT e.topic,e.event_type,e.aggregate_id,e.payload_archived_at,
                       CASE WHEN e.payload_archived_at IS NULL THEN e.payload_json ELSE a.payload_json END AS payload_json,
                       a.payload_sha256 AS archived_payload_hash
                FROM commerce_outbox_event e
                LEFT JOIN commerce_outbox_payload_archive a ON a.event_id=e.event_id
                WHERE e.event_id=? AND e.claim_token=? AND e.fencing_token=? AND e.status='CLAIMED'
                """, claim.eventId(), claim.token(), claim.fencingToken());
        String payload = (String) row.get("payload_json");
        if (payload == null) throw new IllegalStateException(
                "Outbox payload missing for " + claim.eventId() + "; restore the original payload before retrying publication");
        String payloadHash = sha256(payload);
        if (row.get("payload_archived_at") != null && !payloadHash.equals(row.get("archived_payload_hash"))) {
            throw new IllegalStateException("Outbox archive hash mismatch for " + claim.eventId()
                    + "; restore the verified original payload before retrying publication");
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(
                String.valueOf(row.get("topic")), String.valueOf(row.get("aggregate_id")), payload);
        record.headers().add("eventId", claim.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", String.valueOf(row.get("event_type")).getBytes(StandardCharsets.UTF_8));
        record.headers().add("payloadHash", payloadHash.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /**
     * 以单条条件更新取得短租约，并递增单调 fencing token。
     *
     * @param eventId 待抢占事件 ID
     * @return 本 Worker 的所有权凭据；竞争失败返回 {@code null}
     */
    OutboxClaim claim(String eventId) {
        String claim = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE commerce_outbox_event
                SET status='CLAIMED', claim_token=?, fencing_token=COALESCE(fencing_token,0)+1,
                    claimed_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),
                    attempts=attempts+1
                WHERE event_id=?
                   AND ((status IN ('READY','FAILED')
                        AND next_attempt_at <= CURRENT_TIMESTAMP(3))
                       OR (status='CLAIMED'
                           AND claimed_until < CURRENT_TIMESTAMP(3)))
                """, claim, eventId);
        if (claimed != 1) return null;
        Long fencingToken = jdbc.queryForObject("""
                SELECT fencing_token FROM commerce_outbox_event
                WHERE event_id=? AND claim_token=? AND status='CLAIMED'
                """, Long.class, eventId, claim);
        if (fencingToken == null) {
            throw new IllegalStateException("Claimed Commerce outbox event has no fencing token");
        }
        return new OutboxClaim(eventId, claim, fencingToken);
    }

    /** 仅由仍持有同一 claim/fencing token 的 Worker 标记发布成功。 */
    boolean markPublished(OutboxClaim claim) {
        return jdbc.update("""
                UPDATE commerce_outbox_event
                SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP(3),
                    claim_token=NULL, claimed_until=NULL, last_error=NULL
                WHERE event_id=? AND claim_token=? AND fencing_token=? AND status='CLAIMED'
                """, claim.eventId(), claim.token(), claim.fencingToken()) == 1;
    }

    /**
     * 记录发布失败并按 attempts 指数退避；所有权已变化时不更新任何状态。
     */
    boolean markFailed(OutboxClaim claim, Exception error) {
        return jdbc.update("""
                UPDATE commerce_outbox_event
                SET status='FAILED',
                    next_attempt_at=TIMESTAMPADD(
                        SECOND,
                        LEAST(900, CAST(5 * POW(2, LEAST(GREATEST(attempts - 1, 0), 8)) AS UNSIGNED)),
                        CURRENT_TIMESTAMP(3)
                    ),
                    claim_token=NULL, claimed_until=NULL, last_error=?
                WHERE event_id=? AND claim_token=? AND fencing_token=? AND status='CLAIMED'
                """, truncate(error.getMessage()), claim.eventId(),
                claim.token(), claim.fencingToken()) == 1;
    }

    private long unpublished() {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox_event WHERE status <> 'PUBLISHED'",
                Long.class
        );
        return value == null ? 0 : value;
    }

    private double oldestReadyAgeSeconds() {
        Timestamp oldest = jdbc.queryForObject(
            "SELECT MIN(created_at) FROM commerce_outbox_event WHERE status IN ('READY','FAILED','CLAIMED')",
                Timestamp.class
        );
        return oldest == null ? 0 : Math.max(0, (System.currentTimeMillis() - oldest.getTime()) / 1000.0);
    }

    private void count(String outcome) {
        io.micrometer.core.instrument.Counter.builder("citycopilot.commerce.outbox.publish")
                .tag("outcome", outcome)
                .register(metrics)
                .increment();
    }

    private String sha256(String payload) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static Exception rootCause(Exception error) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(error);
        return root instanceof Exception exception ? exception : error;
    }

    private String truncate(String value) {
        String safe = value == null ? "unknown_error" : value;
        return safe.substring(0, Math.min(1024, safe.length()));
    }

    /**
     * 一次 Outbox 抢占的不可转移所有权凭据。
     *
     * @param eventId 事件 ID
     * @param token 本次随机 claim token
     * @param fencingToken 数据库单调递增的隔离令牌
     */
    record OutboxClaim(String eventId, String token, long fencingToken) {
    }
}
