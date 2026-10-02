package com.citycopilot.commerce;

import com.citycopilot.contract.event.EventEnvelope;
import com.citycopilot.contract.commerce.CommerceContracts.AdmissionRepairRequeued;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterView;
import com.citycopilot.contract.commerce.CommerceContracts.OperationsSummary;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Commerce 运维查询与人工恢复服务。
 *
 * <p>提供汇总、死信重放和准入修复重排；所有会改变业务状态的操作都参与路由写租约，
 * 并把操作者与结果写入 Outbox 审计事件。</p>
 */
@Service
public class CommerceOperationsService {
    /** 查询死信/订单状态并写入审计 Outbox。 */
    private final JdbcTemplate jdbc;
    /** 以原始业务身份重新执行准入。 */
    private final CommerceOrderAdmissionService orders;
    /** 解析死信载荷并序列化审计事件。 */
    private final ObjectMapper objectMapper;
    /** 确认当前路由仍允许业务写。 */
    private final CommerceRoutingGuard routing;
    /** 管理持久化准入的人工修复状态。 */
    private final CommerceAdmissionDurabilityService admissions;
    /** 让运维写与路由排空互斥；测试构造可为空。 */
    private final CommerceRuntimeMutationLeases runtimeLeases;

    @Autowired
    public CommerceOperationsService(
            JdbcTemplate jdbc,
            CommerceOrderAdmissionService orders,
            ObjectMapper objectMapper,
            CommerceRoutingGuard routing,
            CommerceAdmissionDurabilityService admissions,
            CommerceRuntimeMutationLeases runtimeLeases
    ) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.objectMapper = objectMapper;
        this.routing = routing;
        this.admissions = admissions;
        this.runtimeLeases = runtimeLeases;
    }

    CommerceOperationsService(
            JdbcTemplate jdbc,
            CommerceOrderAdmissionService orders,
            ObjectMapper objectMapper,
            CommerceRoutingGuard routing,
            CommerceAdmissionDurabilityService admissions
    ) {
        this(jdbc, orders, objectMapper, routing, admissions, null);
    }

    /**
     * 汇总订单和 Voucher 指标。
     *
     * @param fromInclusive 统计窗口包含端
     * @param toExclusive 统计窗口排除端
     * @return 订单总量、窗口订单量及 Voucher 状态计数
     */
    public OperationsSummary summary(Instant fromInclusive, Instant toExclusive) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "A valid summary time range is required",
                    400
            );
        }
        return jdbc.queryForObject("""
                SELECT
                    o.orders,
                    o.today_orders,
                    o.unpaid_orders,
                    o.cancelled_orders,
                    v.vouchers,
                    v.seckill_vouchers,
                    v.active_vouchers
                FROM (
                    SELECT
                        COUNT(*) AS orders,
                        COUNT(CASE WHEN create_time >= ? AND create_time < ? THEN 1 END) AS today_orders,
                        COUNT(CASE WHEN status = 1 THEN 1 END) AS unpaid_orders,
                        COUNT(CASE WHEN status = 4 THEN 1 END) AS cancelled_orders
                    FROM commerce_order
                ) o
                CROSS JOIN (
                    SELECT
                        COUNT(*) AS vouchers,
                        COUNT(CASE WHEN type = 1 THEN 1 END) AS seckill_vouchers,
                        COUNT(CASE WHEN status = 1 THEN 1 END) AS active_vouchers
                    FROM commerce_voucher
                ) v
                """,
                (rs, rowNum) -> new OperationsSummary(rs.getLong("orders"), rs.getLong("today_orders"),
                        rs.getLong("unpaid_orders"), rs.getLong("cancelled_orders"), rs.getLong("vouchers"),
                        rs.getLong("seckill_vouchers"), rs.getLong("active_vouchers")),
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive)
        );
    }

    /**
     * 查询最近的 Stream 死信及对应恢复阶段。
     *
     * @param limit 期望条数，服务端限制到 1..100
     * @return 按创建时间倒序的死信摘要
     */
    public List<DeadLetterView> deadLetters(Integer limit) {
        int bounded = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        return jdbc.query("""
                SELECT dead.source_id,dead.error_message,dead.retry_count,
                       dead.created_at,dead.replayed_at,
                       recovery.phase AS recovery_phase
                FROM commerce_dead_letter dead
                LEFT JOIN commerce_order_recovery recovery
                  ON recovery.source_id=dead.source_id
                ORDER BY dead.created_at DESC
                LIMIT ?
                """, (row, rowNumber) -> new DeadLetterView(row.getString("source_id"),
                        row.getString("error_message"), row.getInt("retry_count"), row.getTimestamp("created_at"),
                        row.getTimestamp("replayed_at"), row.getString("recovery_phase")), bounded);
    }

    /**
     * 在事务中重放死信准入并记录审计事件。
     *
     * @param sourceId 死信/恢复记录的稳定来源 ID
     * @param actor 运维操作者标识
     * @return 重放结果；已重放时幂等返回 {@code already_replayed}
     */
    @Transactional
    public DeadLetterReplayResult replayDeadLetter(String sourceId, String actor) {
        beginTransactionalMutation();
        if (sourceId == null || sourceId.isBlank()) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "sourceId is required",
                    400
            );
        }
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT dead.source_id,dead.payload_json,dead.replayed_at,
                           recovery.phase AS recovery_phase
                    FROM commerce_dead_letter dead
                    LEFT JOIN commerce_order_recovery recovery
                      ON recovery.source_id=dead.source_id
                    WHERE dead.source_id=?
                    """, sourceId);
        } catch (EmptyResultDataAccessException missing) {
            throw new CommerceApiException(
                    "COMMERCE_DEAD_LETTER_NOT_FOUND",
                    "Dead letter not found",
                    404
            );
        }
        if (row.get("replayed_at") != null) {
            return alreadyReplayed(sourceId);
        }
        if ("REPAIR_REQUIRED".equals(String.valueOf(row.get("recovery_phase")))) {
            throw new CommerceApiException(
                    "COMMERCE_INVENTORY_REPAIR_REQUIRED",
                    "Repair and verify this voucher inventory before replaying the dead letter",
                    409
            );
        }
        Map<String, Object> payload = payload(row.get("payload_json"));
        long orderId = positiveLong(payload.get("orderId"), "orderId");
        long voucherId = positiveLong(payload.get("voucherId"), "voucherId");
        long userId = positiveLong(payload.get("userId"), "userId");
        String idempotencyKey = payload.get("idempotencyKey") == null
                ? "dead-letter-order:" + orderId
                : String.valueOf(payload.get("idempotencyKey"));
        routing.requireAdmissionActive(voucherId);
        // 复用原 orderId、userId、voucherId 和幂等键，避免“修复”变成一笔新订单。
        Map<String, Object> accepted = orders.replayAdmission(
                orderId,
                voucherId,
                userId,
                idempotencyKey
        );
        int marked = jdbc.update("""
                UPDATE commerce_dead_letter
                SET replayed_at=CURRENT_TIMESTAMP(3)
                WHERE source_id=? AND replayed_at IS NULL
                """, sourceId);
        if (marked != 1) {
            return alreadyReplayed(sourceId);
        }
        appendReplayAudit(sourceId, orderId, voucherId, userId, actor);
        // 原 HTTP 响应的 status 一直是重放状态；completed 分支只透传订单 version。
        return new DeadLetterReplayResult("replayed", sourceId, (String) accepted.get("orderId"),
                (String) accepted.get("state"), (String) accepted.get("code"),
                (String) accepted.get("idempotencyKey"),
                accepted.get("version") instanceof Number version ? version.intValue() : null);
    }

    /**
     * 将 REPAIR_REQUIRED 准入重新放回自动恢复队列，并在同一事务写审计事件。
     *
     * @param orderId 待修复订单 ID
     * @param actor 运维操作者标识
     * @return 重排结果
     */
    @Transactional
    public AdmissionRepairRequeued requeueAdmissionRepair(long orderId, String actor) {
        beginTransactionalMutation();
        if (orderId <= 0) {
            throw new CommerceApiException(
                    "COMMERCE_INVALID_ARGUMENT",
                    "A positive orderId is required",
                    400
            );
        }
        appendAdmissionRepairRequeueAudit(orderId, actor);
        admissions.requeueRepairRequired(orderId);
        return new AdmissionRepairRequeued("requeued", String.valueOf(orderId));
    }

    /** 将死信 JSON 解码为业务字段；无法可靠解释的载荷禁止重放。 */
    private Map<String, Object> payload(Object raw) {
        try {
            Map<String, Object> value = objectMapper.readValue(
                    String.valueOf(raw),
                    new TypeReference<LinkedHashMap<String, Object>>() {}
            );
            if (value == null) {
                throw new IllegalArgumentException("payload is null");
            }
            return value;
        } catch (Exception error) {
            throw new CommerceApiException(
                    "COMMERCE_DEAD_LETTER_INVALID",
                    "Dead-letter payload cannot be replayed",
                    409
            );
        }
    }

    /**
     * 为当前事务取得运行时写租约，并注册事务完成后释放。
     *
     * <p>先取租约再检查路由，避免排空任务与新事务交错；若检查失败立即释放。</p>
     */
    private void beginTransactionalMutation() {
        if (runtimeLeases == null) return;
        CommerceRuntimeMutationLeases.MutationLease lease = runtimeLeases.acquireMutation();
        try {
            routing.requireRuntimeMutationActive();
            runtimeLeases.releaseAfterTransaction(lease);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private long positiveLong(Object value, String name) {
        try {
            long result = value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(value));
            if (result <= 0) {
                throw new NumberFormatException();
            }
            return result;
        } catch (RuntimeException error) {
            throw new CommerceApiException(
                    "COMMERCE_DEAD_LETTER_INVALID",
                    "Dead-letter " + name + " is invalid",
                    409
            );
        }
    }

    private DeadLetterReplayResult alreadyReplayed(String sourceId) {
        return new DeadLetterReplayResult("already_replayed", sourceId, null, null, null, null, null);
    }

    /** 将死信重放事实写入运维 Outbox；与 replayed_at 更新共享事务。 */
    private void appendReplayAudit(
            String sourceId,
            long orderId,
            long voucherId,
            long userId,
            String actor
    ) {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope<Map<String, Object>> event = new EventEnvelope<>(
                eventId, "commerce.dead-letter.replayed", 1, "commerce_dead_letter", sourceId, 1,
                "commerce-service", Instant.now().toString(), String.valueOf(orderId), Map.of(
                "sourceId", sourceId,
                "orderId", orderId,
                "voucherId", voucherId,
                "userId", userId,
                "actor", actor == null || actor.isBlank() ? "unknown" : actor
        ));
        try {
            // 审计先落 MySQL Outbox，RocketMQ 发布失败不影响重放事实的持久性。
            jdbc.update("""
                    INSERT INTO commerce_outbox_event(
                        event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                        topic,payload_json,status
                    ) VALUES (?,?,?,?,?,'citycopilot-ops-events-v1',?,'READY')
                    """,
                    eventId,
                    event.eventType(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.aggregateVersion(),
                    objectMapper.writeValueAsString(event)
            );
        } catch (Exception error) {
            throw new IllegalStateException("Unable to persist dead-letter replay audit", error);
        }
    }

    /** 将人工重排准入的操作写入运维 Outbox。 */
    private void appendAdmissionRepairRequeueAudit(long orderId, String actor) {
        String eventId = UUID.randomUUID().toString();
        EventEnvelope<Map<String, Object>> event = new EventEnvelope<>(
                eventId, "commerce.admission.repair.requeued", 1, "commerce_admission_repair_requeue", eventId, 1,
                "commerce-service", Instant.now().toString(), String.valueOf(orderId), Map.of(
                "orderId", String.valueOf(orderId),
                "actor", actor == null || actor.isBlank() ? "unknown" : actor
        ));
        try {
            jdbc.update("""
                    INSERT INTO commerce_outbox_event(
                        event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                        topic,payload_json,status
                    ) VALUES (?,?,?,?,?,'citycopilot-ops-events-v1',?,'READY')
                    """,
                    eventId,
                    event.eventType(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.aggregateVersion(),
                    objectMapper.writeValueAsString(event)
            );
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Unable to persist admission repair-requeue audit",
                    error
            );
        }
    }
}
