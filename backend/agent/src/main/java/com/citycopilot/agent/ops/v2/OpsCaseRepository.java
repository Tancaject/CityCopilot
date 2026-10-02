package com.citycopilot.agent.ops.v2;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ops Case v2 的事务与并发控制仓储。
 *
 * <p>集中实现事件 Inbox 幂等、事故聚合、状态机乐观锁、worker 租约、证据 TTL、计划版本、一次性审批、执行去重和审计时间线。上层不得绕过这些数据库条件直接推进状态。</p>
 */
@Repository
public class OpsCaseRepository {
    /** 执行参数化 SQL 和条件更新的 JDBC 客户端。 */
    private final JdbcTemplate jdbcTemplate;
    /** 序列化证据、诊断、步骤和执行结果。 */
    private final ObjectMapper objectMapper;

    public OpsCaseRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等接受事件并创建或关联一个开放 case。
     *
     * @param consumerGroup 消费组，参与 Inbox 唯一键
     * @param eventId 事件唯一标识
     * @param eventType 版本化事件类型
     * @param payloadHash 原始载荷摘要
     * @param payloadJson 原始事件信封
     * @param incidentKey 聚合同一事故的业务键
     * @return 新建/并发围栏、重复或隔离决定及关联 case
     */
    @Transactional
    public EventAcceptance acceptEvent(
            String consumerGroup,
            String eventId,
            String eventType,
            String payloadHash,
            String payloadJson,
            String incidentKey
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO ops_event_inbox(
                    consumer_group, event_id, event_type, payload_hash, payload_json, status
                ) VALUES (?, ?, ?, ?, ?, 'RECEIVED')
                """, consumerGroup, eventId, eventType, payloadHash, payloadJson);
        // 同一消费组和 eventId 已存在时比较载荷哈希，防止标识复用掩盖不同事实。
        if (inserted == 0) {
            Map<String, Object> existing = jdbcTemplate.queryForMap("""
                    SELECT payload_hash, status
                    FROM ops_event_inbox
                    WHERE consumer_group=? AND event_id=?
                    """, consumerGroup, eventId);
            if (!payloadHash.equals(existing.get("payload_hash"))) {
                jdbcTemplate.update("""
                        UPDATE ops_event_inbox
                        SET status='QUARANTINED',
                            quarantine_reason='same eventId received with different payload',
                            processed_at=CURRENT_TIMESTAMP(3)
                        WHERE consumer_group=? AND event_id=?
                        """, consumerGroup, eventId);
                return new EventAcceptance(null, EventDisposition.QUARANTINED);
            }
            return new EventAcceptance(findByEventId(eventId).orElse(null), EventDisposition.DUPLICATE);
        }

        OpsCaseRecord opsCase;
        try {
            String caseId = UUID.randomUUID().toString();
            jdbcTemplate.update("""
                    INSERT INTO ops_case(id, incident_key, event_id, case_type, status)
                    VALUES (?, ?, ?, 'STOCK_DISCREPANCY', 'OPEN')
                    """, caseId, incidentKey, eventId);
            appendTransition(caseId, null, OpsCaseStatus.OPEN, "ops-event-consumer", "event accepted", List.of());
            opsCase = require(caseId);
        } catch (DuplicateKeyException race) {
            // eventId 或开放 incident 唯一键竞争失败时复用赢家，避免并发消息创建两个 case。
            opsCase = findByEventId(eventId)
                    .or(() -> findOpenByIncidentKey(incidentKey))
                    .orElseThrow(() -> race);
        }
        jdbcTemplate.update("""
                UPDATE ops_event_inbox
                SET status='PROCESSED', processed_at=CURRENT_TIMESTAMP(3)
                WHERE consumer_group=? AND event_id=?
                """, consumerGroup, eventId);
        return new EventAcceptance(opsCase, EventDisposition.CREATED_OR_FENCED);
    }

    /** @return 指定 case 当前投影，不存在时为空 */
    public Optional<OpsCaseRecord> find(String caseId) {
        List<OpsCaseRecord> rows = jdbcTemplate.query("""
                SELECT id, incident_key, event_id, case_type, status, version, current_step,
                       retry_count, cancellation_requested, last_error, created_at, updated_at
                FROM ops_case WHERE id=?
                """, (rs, row) -> mapCase(rs), caseId);
        return rows.stream().findFirst();
    }

    /** @return 指定 case；不存在时抛出参数异常 */
    public OpsCaseRecord require(String caseId) {
        return find(caseId).orElseThrow(() -> new IllegalArgumentException("Ops Case not found: " + caseId));
    }

    public Optional<OpsCaseRecord> findByEventId(String eventId) {
        return queryOne("event_id=?", eventId);
    }

    /** @return 同一事故键尚未进入终态的 case */
    public Optional<OpsCaseRecord> findOpenByIncidentKey(String incidentKey) {
        return queryOne("incident_key=? AND open_incident_marker=1", incidentKey);
    }

    /** @return 最近创建的 case，limit 被限制在 1 到 100 */
    public List<OpsCaseRecord> list(int limit) {
        return jdbcTemplate.query("""
                SELECT id, incident_key, event_id, case_type, status, version, current_step,
                       retry_count, cancellation_requested, last_error, created_at, updated_at
                FROM ops_case
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, row) -> mapCase(rs), Math.max(1, Math.min(limit, 100)));
    }

    /**
     * 查询已到执行时间且处于 worker 可推进状态的候选 case。
     */
    public List<OpsCaseRecord> workCandidates(int limit) {
        return jdbcTemplate.query("""
                SELECT id, incident_key, event_id, case_type, status, version, current_step,
                       retry_count, cancellation_requested, last_error, created_at, updated_at
                FROM ops_case
                WHERE status IN ('OPEN','APPROVED','VERIFYING','RETRY_WAIT')
                  AND (next_retry_at IS NULL OR next_retry_at <= ?)
                ORDER BY updated_at
                LIMIT ?
                """,
                (rs, row) -> mapCase(rs),
                Timestamp.from(Instant.now()),
                Math.max(1, Math.min(limit, 50))
        );
    }

    /**
     * 竞争或续租 case 级 worker 租约。
     *
     * @return 租约为空、已过期或已属于同一 owner 并成功更新时为 true
     */
    public boolean tryAcquireLease(String caseId, String owner, Duration duration) {
        return jdbcTemplate.update("""
                UPDATE ops_case
                SET lease_owner=?, lease_until=?
                WHERE id=? AND (
                    lease_until IS NULL OR lease_until < ? OR lease_owner=?
                )
                """,
                owner,
                Timestamp.from(Instant.now().plus(duration)),
                caseId,
                Timestamp.from(Instant.now()),
                owner
        ) == 1;
    }

    /** 仅允许租约持有者释放自己的 case 租约。 */
    public void releaseLease(String caseId, String owner) {
        jdbcTemplate.update("""
                UPDATE ops_case
                SET lease_owner=NULL, lease_until=NULL
                WHERE id=? AND lease_owner=?
                """, caseId, owner);
    }

    /**
     * 以状态白名单和版本条件原子推进 case，并追加不可变转移记录。
     *
     * @throws OptimisticCaseConflictException 其他线程已修改版本或状态
     */
    @Transactional
    public OpsCaseRecord transition(
            OpsCaseRecord current,
            OpsCaseStatus target,
            String actor,
            String reason,
            List<String> evidenceIds
    ) {
        current.status().requireTransitionTo(target);
        int updated = jdbcTemplate.update("""
                UPDATE ops_case
                SET status=?, version=version+1, last_error=NULL,
                    resolved_at=CASE WHEN ? IN ('SUCCEEDED','CANCELLED') THEN CURRENT_TIMESTAMP(3) ELSE resolved_at END
                WHERE id=? AND version=? AND status=?
                """,
                target.name(),
                target.name(),
                current.id(),
                current.version(),
                current.status().name()
        );
        if (updated != 1) {
            throw new OptimisticCaseConflictException(current.id());
        }
        appendTransition(current.id(), current.status(), target, actor, reason, evidenceIds);
        return require(current.id());
    }

    /** 对仍开放的 case 设置协作式取消标志并推进版本。 */
    public void requestCancellation(String caseId) {
        int updated = jdbcTemplate.update("""
                UPDATE ops_case
                SET cancellation_requested=1, version=version+1
                WHERE id=? AND open_incident_marker=1
                """, caseId);
        if (updated != 1) {
            throw new IllegalStateException("Case is not open or does not exist");
        }
    }

    /**
     * 将失败 case 放入延迟重试，并根据失败阶段记录恢复位置。
     */
    public void scheduleRetry(OpsCaseRecord current, String error, Duration delay) {
        int updated = jdbcTemplate.update("""
                UPDATE ops_case
                SET status='RETRY_WAIT', version=version+1, retry_count=retry_count+1,
                    current_step=CASE WHEN ?='VERIFYING' THEN 2 ELSE 0 END,
                    last_error=?, next_retry_at=?, lease_owner=NULL, lease_until=NULL
                WHERE id=? AND version=? AND status=?
                """,
                current.status().name(),
                error == null ? "unknown_error" : error.substring(0, Math.min(error.length(), 1024)),
                Timestamp.from(Instant.now().plus(delay)),
                current.id(),
                current.version(),
                current.status().name()
        );
        if (updated != 1) {
            throw new OptimisticCaseConflictException(current.id());
        }
        appendTransition(
                current.id(),
                current.status(),
                OpsCaseStatus.RETRY_WAIT,
                "ops-worker",
                error,
                List.of()
        );
    }

    /** 清除取消/错误状态并立即安排一次人工触发的重试。 */
    @Transactional
    public OpsCaseRecord manualRetry(OpsCaseRecord current, String actor) {
        current.status().requireTransitionTo(OpsCaseStatus.RETRY_WAIT);
        int updated = jdbcTemplate.update("""
                UPDATE ops_case
                SET status='RETRY_WAIT', version=version+1, next_retry_at=?,
                    current_step=0, cancellation_requested=0, last_error=NULL
                WHERE id=? AND version=? AND status=?
                """,
                Timestamp.from(Instant.now()),
                current.id(),
                current.version(),
                current.status().name()
        );
        if (updated != 1) {
            throw new OptimisticCaseConflictException(current.id());
        }
        appendTransition(
                current.id(),
                current.status(),
                OpsCaseStatus.RETRY_WAIT,
                actor,
                "manual retry requested",
                List.of()
        );
        return require(current.id());
    }

    /**
     * 将超过重试边界或无法恢复的来源写入死信表，保留原载荷与错误摘要。
     */
    public void deadLetter(
            String sourceType,
            String sourceId,
            Object payload,
            String error,
            int retryCount
    ) {
        jdbcTemplate.update("""
                INSERT INTO ops_dead_letter(
                    source_type, source_id, payload_json, error_message, retry_count
                ) VALUES (?, ?, ?, ?, ?)
                """,
                sourceType,
                sourceId,
                payload == null ? null : json(payload),
                truncate(error, 1024),
                retryCount
        );
    }

    /**
     * 保存带哈希与失效时间的证据快照。
     *
     * @return 新生成的 evidenceId
     */
    public String saveEvidence(
            String caseId,
            String evidenceType,
            String source,
            String payloadJson,
            String payloadHash,
            Instant collectedAt,
            Duration ttl
    ) {
        String evidenceId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO ops_evidence(
                    id, case_id, evidence_type, source, payload_json, payload_hash,
                    collected_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                evidenceId,
                caseId,
                evidenceType,
                source,
                payloadJson,
                payloadHash,
                Timestamp.from(collectedAt),
                Timestamp.from(collectedAt.plus(ttl))
        );
        return evidenceId;
    }

    /** @return case 的全部历史证据，包括已过期记录 */
    public List<Map<String, Object>> evidence(String caseId) {
        return readEvidence(caseId, false);
    }

    /** @return 当前时刻尚未过期、可用于绑定新计划的证据 */
    public List<Map<String, Object>> validEvidence(String caseId) {
        return readEvidence(caseId, true);
    }

    private List<Map<String, Object>> readEvidence(String caseId, boolean validOnly) {
        var rows = jdbcTemplate.queryForList("""
                SELECT e.id,e.evidence_type,e.source,
                       CASE WHEN e.payload_archived_at IS NULL THEN e.payload_json ELSE a.payload_json END AS payload_json,
                       e.payload_hash,e.collected_at,e.expires_at,
                       CASE WHEN e.payload_archived_at IS NULL THEN 'valid'
                            WHEN a.payload_sha256=SHA2(CAST(a.payload_json AS BINARY),256) THEN 'valid'
                            ELSE 'invalid' END AS archive_integrity
                FROM ops_evidence e
                LEFT JOIN ops_evidence_payload_archive a ON a.evidence_id=e.id
                WHERE e.case_id=? %s
                ORDER BY e.collected_at %s
                """.formatted(validOnly ? "AND e.expires_at > ?" : "", validOnly ? "DESC" : ""),
                validOnly ? new Object[]{caseId, Timestamp.from(Instant.now())} : new Object[]{caseId});
        for (var row : rows) {
            if (!"valid".equals(row.remove("archive_integrity"))) {
                throw new IllegalStateException("Archived Ops evidence missing or changed: " + row.get("id")
                        + "; restore its verified original payload before reading the case");
            }
        }
        return rows;
    }

    /**
     * 创建递增版本计划，并把此前仍活动的计划标为已取代。
     */
    @Transactional
    public OpsPlanRecord createPlan(
            OpsCaseRecord current,
            String source,
            Map<String, Object> diagnosis,
            List<OpsPlanStep> steps,
            String planHash,
            String actor
    ) {
        int version = Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT MAX(plan_version) FROM ops_plan WHERE case_id=?",
                Integer.class,
                current.id()
        )).orElse(0) + 1;
        String planId = UUID.randomUUID().toString();
        // 同一 case 只有最新候选计划可继续审批，旧草稿和批准计划统一失效。
        jdbcTemplate.update(
                "UPDATE ops_plan SET status='SUPERSEDED', superseded_at=CURRENT_TIMESTAMP(3) "
                        + "WHERE case_id=? AND status IN ('DRAFT','AWAITING_APPROVAL','APPROVED')",
                current.id()
        );
        jdbcTemplate.update("""
                INSERT INTO ops_plan(
                    id, case_id, plan_version, source, diagnosis_json, steps_json,
                    plan_hash, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                """,
                planId,
                current.id(),
                version,
                source,
                json(diagnosis),
                json(steps),
                planHash,
                actor
        );
        return plan(planId);
    }

    /** @return case 的最高版本计划 */
    public Optional<OpsPlanRecord> latestPlan(String caseId) {
        List<String> ids = jdbcTemplate.queryForList("""
                SELECT id FROM ops_plan
                WHERE case_id=?
                ORDER BY plan_version DESC
                LIMIT 1
                """, String.class, caseId);
        return ids.stream().findFirst().map(this::plan);
    }

    /**
     * 将草稿计划置为待审批并创建绑定 planHash 的限时审批记录。
     *
     * @return 新 approvalId
     */
    @Transactional
    public String requestApproval(OpsCaseRecord current, OpsPlanRecord plan, Duration ttl) {
        jdbcTemplate.update(
                "UPDATE ops_plan SET status='AWAITING_APPROVAL' WHERE id=? AND status='DRAFT'",
                plan.id()
        );
        String approvalId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO ops_approval(
                    id, case_id, plan_id, plan_hash, decision, expires_at
                ) VALUES (?, ?, ?, ?, 'PENDING', ?)
                """,
                approvalId,
                current.id(),
                plan.id(),
                plan.planHash(),
                Timestamp.from(Instant.now().plus(ttl))
        );
        return approvalId;
    }

    /**
     * 以哈希、状态、过期时间和审批版本条件一次性记录人工决定。
     *
     * @param expectedPlanHash 审批人实际查看的计划摘要
     * @return 批准后为 APPROVED，拒绝后回到 MANUAL_REVIEW 的 case
     */
    @Transactional
    public OpsCaseRecord decideApproval(
            String approvalId,
            String expectedPlanHash,
            boolean approved,
            String actor
    ) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT case_id, plan_id, plan_hash, decision, expires_at, consumed_at, version
                FROM ops_approval WHERE id=?
                """, approvalId);
        if (!expectedPlanHash.equals(row.get("plan_hash"))) {
            throw new IllegalStateException("Approval plan hash does not match");
        }
        if (!"PENDING".equals(row.get("decision")) || row.get("consumed_at") != null) {
            throw new IllegalStateException("Approval was already decided or consumed");
        }
        if (((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw new IllegalStateException("Approval expired");
        }
        // 版本条件更新让并发的第二个审批决定失败，而不是覆盖首个决定。
        int updated = jdbcTemplate.update("""
                UPDATE ops_approval
                SET decision=?, decided_by=?, decided_at=CURRENT_TIMESTAMP(3), version=version+1
                WHERE id=? AND decision='PENDING' AND consumed_at IS NULL AND version=?
                """,
                approved ? "APPROVED" : "REJECTED",
                actor,
                approvalId,
                ((Number) row.get("version")).intValue()
        );
        if (updated != 1) {
            throw new IllegalStateException("Approval decision lost a concurrent update");
        }
        OpsCaseRecord current = require(String.valueOf(row.get("case_id")));
        if (!approved) {
            jdbcTemplate.update("UPDATE ops_plan SET status='REJECTED' WHERE id=?", row.get("plan_id"));
            return transition(current, OpsCaseStatus.MANUAL_REVIEW, actor, "plan rejected", List.of());
        }
        jdbcTemplate.update("UPDATE ops_plan SET status='APPROVED' WHERE id=?", row.get("plan_id"));
        return transition(current, OpsCaseStatus.APPROVED, actor, "plan approved", List.of());
    }

    /**
     * 在开始写工具前原子消费已批准且未过期的审批。
     *
     * @return 首次有效消费成功时为 true
     */
    @Transactional
    public boolean consumeApproval(String caseId, String planId, String planHash) {
        return jdbcTemplate.update("""
                UPDATE ops_approval
                SET consumed_at=CURRENT_TIMESTAMP(3), version=version+1
                WHERE case_id=? AND plan_id=? AND plan_hash=?
                  AND decision='APPROVED' AND consumed_at IS NULL
                  AND expires_at > ?
                """, caseId, planId, planHash, Timestamp.from(Instant.now())) == 1;
    }

    /**
     * 以执行 id/幂等约束登记 RUNNING 步骤。
     *
     * @return 首次插入为 true；重复执行记录被 {@code INSERT IGNORE} 拒绝
     */
    public boolean beginExecution(
            String executionId,
            String caseId,
            String planId,
            int stepIndex,
            OpsPlanStep step,
            String idempotencyKey
    ) {
        return jdbcTemplate.update("""
                INSERT IGNORE INTO ops_execution(
                    id, case_id, plan_id, step_index, tool_name, arguments_json,
                    idempotency_key, status, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP(3))
                """,
                executionId,
                caseId,
                planId,
                stepIndex,
                step.toolName(),
                json(step.arguments()),
                idempotencyKey
        ) == 1;
    }

    /** 仅将仍处于 RUNNING 的执行记录更新为成功或失败终态。 */
    public void finishExecution(String executionId, boolean success, Object result, String errorCode) {
        jdbcTemplate.update("""
                UPDATE ops_execution
                SET status=?, result_json=?, error_code=?, completed_at=CURRENT_TIMESTAMP(3)
                WHERE id=? AND status='RUNNING'
                """,
                success ? "SUCCEEDED" : "FAILED",
                result == null ? null : json(result),
                errorCode,
                executionId
        );
    }

    /** @return case 的工具执行审计记录，按开始时间和步骤排序 */
    public List<Map<String, Object>> executions(String caseId) {
        return jdbcTemplate.queryForList("""
                SELECT id, plan_id, step_index, tool_name, arguments_json, status,
                       result_json, error_code, started_at, completed_at
                FROM ops_execution
                WHERE case_id=?
                ORDER BY started_at, step_index
                """, caseId);
    }

    /** @return case 的完整状态转移时间线 */
    public List<Map<String, Object>> transitions(String caseId) {
        return jdbcTemplate.queryForList("""
                SELECT id, from_status, to_status, actor, reason, evidence_ids_json, created_at
                FROM ops_transition
                WHERE case_id=?
                ORDER BY id
                """, caseId);
    }

    private OpsPlanRecord plan(String planId) {
        return jdbcTemplate.queryForObject("""
                SELECT id, case_id, plan_version, source, diagnosis_json, steps_json,
                       plan_hash, status, created_at
                FROM ops_plan WHERE id=?
                """,
                (rs, row) -> new OpsPlanRecord(
                        rs.getString("id"),
                        rs.getString("case_id"),
                        rs.getInt("plan_version"),
                        rs.getString("source"),
                        readMap(rs.getString("diagnosis_json")),
                        readSteps(rs.getString("steps_json")),
                        rs.getString("plan_hash"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                planId
        );
    }

    private Optional<OpsCaseRecord> queryOne(String predicate, Object value) {
        List<OpsCaseRecord> rows = jdbcTemplate.query("""
                SELECT id, incident_key, event_id, case_type, status, version, current_step,
                       retry_count, cancellation_requested, last_error, created_at, updated_at
                FROM ops_case WHERE %s LIMIT 1
                """.formatted(predicate), (rs, row) -> mapCase(rs), value);
        return rows.stream().findFirst();
    }

    private OpsCaseRecord mapCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OpsCaseRecord(
                rs.getString("id"),
                rs.getString("incident_key"),
                rs.getString("event_id"),
                rs.getString("case_type"),
                OpsCaseStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getInt("current_step"),
                rs.getInt("retry_count"),
                rs.getBoolean("cancellation_requested"),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    /** 在同一事务中记录状态来源、目标、操作者、原因和证据引用。 */
    private void appendTransition(
            String caseId,
            OpsCaseStatus from,
            OpsCaseStatus to,
            String actor,
            String reason,
            List<String> evidenceIds
    ) {
        jdbcTemplate.update("""
                INSERT INTO ops_transition(
                    case_id, from_status, to_status, actor, reason, evidence_ids_json
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                caseId,
                from == null ? null : from.name(),
                to.name(),
                actor,
                reason,
                json(evidenceIds)
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Ops persistence payload", error);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read Ops diagnosis", error);
        }
    }

    private List<OpsPlanStep> readSteps(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read Ops plan steps", error);
        }
    }

    private String truncate(String value, int maxLength) {
        String safe = value == null ? "unknown_error" : value;
        return safe.substring(0, Math.min(safe.length(), maxLength));
    }

    /**
     * Inbox 接受结果。
     *
     * @param opsCase 新建或复用的 case；隔离事件时为空
     * @param disposition 幂等与冲突处理决定
     */
    public record EventAcceptance(OpsCaseRecord opsCase, EventDisposition disposition) {
    }

    /** 事件进入 Inbox 后的处理分类。 */
    public enum EventDisposition {
        /** 创建了 case，或被并发唯一键围栏后复用了赢家。 */
        CREATED_OR_FENCED,
        /** 相同 eventId 与相同载荷已处理。 */
        DUPLICATE,
        /** 相同 eventId 携带不同载荷，已隔离等待人工处理。 */
        QUARANTINED
    }

    /** case 状态或版本被并发更新时抛出的明确冲突异常。 */
    public static class OptimisticCaseConflictException extends RuntimeException {
        public OptimisticCaseConflictException(String caseId) {
            super("Ops Case version changed concurrently: " + caseId);
        }
    }
}
