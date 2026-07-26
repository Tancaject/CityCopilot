package com.citycopilot.agent.ops.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Repository
public class OpsCaseRepository {
    private static final List<String> OPEN_STATUSES = List.of(
            "OPEN", "COLLECTING_EVIDENCE", "MANUAL_REVIEW", "PLAN_READY",
            "AWAITING_APPROVAL", "APPROVED", "EXECUTING", "VERIFYING", "RETRY_WAIT"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OpsCaseRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

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

    public Optional<OpsCaseRecord> find(String caseId) {
        List<OpsCaseRecord> rows = jdbcTemplate.query("""
                SELECT id, incident_key, event_id, case_type, status, version, current_step,
                       retry_count, cancellation_requested, last_error, created_at, updated_at
                FROM ops_case WHERE id=?
                """, (rs, row) -> mapCase(rs), caseId);
        return rows.stream().findFirst();
    }

    public OpsCaseRecord require(String caseId) {
        return find(caseId).orElseThrow(() -> new IllegalArgumentException("Ops Case not found: " + caseId));
    }

    public Optional<OpsCaseRecord> findByEventId(String eventId) {
        return queryOne("event_id=?", eventId);
    }

    public Optional<OpsCaseRecord> findOpenByIncidentKey(String incidentKey) {
        return queryOne("incident_key=? AND open_incident_marker=1", incidentKey);
    }

    public List<OpsCaseRecord> list(int limit) {
        return jdbcTemplate.query("""
                SELECT id, incident_key, event_id, case_type, status, version, current_step,
                       retry_count, cancellation_requested, last_error, created_at, updated_at
                FROM ops_case
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, row) -> mapCase(rs), Math.max(1, Math.min(limit, 100)));
    }

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

    public void releaseLease(String caseId, String owner) {
        jdbcTemplate.update("""
                UPDATE ops_case
                SET lease_owner=NULL, lease_until=NULL
                WHERE id=? AND lease_owner=?
                """, caseId, owner);
    }

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

    public List<Map<String, Object>> evidence(String caseId) {
        return jdbcTemplate.queryForList("""
                SELECT id, evidence_type, source, payload_json, payload_hash,
                       collected_at, expires_at
                FROM ops_evidence
                WHERE case_id=?
                ORDER BY collected_at
                """, caseId);
    }

    public List<Map<String, Object>> validEvidence(String caseId) {
        return jdbcTemplate.queryForList("""
                SELECT id, evidence_type, source, payload_json, payload_hash,
                       collected_at, expires_at
                FROM ops_evidence
                WHERE case_id=? AND expires_at > ?
                ORDER BY collected_at DESC
                """, caseId, Timestamp.from(Instant.now()));
    }

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

    public Optional<OpsPlanRecord> latestPlan(String caseId) {
        List<String> ids = jdbcTemplate.queryForList("""
                SELECT id FROM ops_plan
                WHERE case_id=?
                ORDER BY plan_version DESC
                LIMIT 1
                """, String.class, caseId);
        return ids.stream().findFirst().map(this::plan);
    }

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

    public List<Map<String, Object>> executions(String caseId) {
        return jdbcTemplate.queryForList("""
                SELECT id, plan_id, step_index, tool_name, arguments_json, status,
                       result_json, error_code, started_at, completed_at
                FROM ops_execution
                WHERE case_id=?
                ORDER BY started_at, step_index
                """, caseId);
    }

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

    public record EventAcceptance(OpsCaseRecord opsCase, EventDisposition disposition) {
    }

    public enum EventDisposition {
        CREATED_OR_FENCED,
        DUPLICATE,
        QUARANTINED
    }

    public static class OptimisticCaseConflictException extends RuntimeException {
        public OptimisticCaseConflictException(String caseId) {
            super("Ops Case version changed concurrently: " + caseId);
        }
    }
}
