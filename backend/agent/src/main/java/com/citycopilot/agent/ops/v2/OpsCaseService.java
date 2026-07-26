package com.citycopilot.agent.ops.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpsCaseService {
    private final OpsCaseRepository repository;
    private final OpsPlanValidator planValidator;
    private final ApprovalPolicyEngine approvalPolicyEngine;
    private final OpsV2Properties properties;
    private final ObjectMapper objectMapper;

    public OpsCaseService(
            OpsCaseRepository repository,
            OpsPlanValidator planValidator,
            ApprovalPolicyEngine approvalPolicyEngine,
            OpsV2Properties properties,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.planValidator = planValidator;
        this.approvalPolicyEngine = approvalPolicyEngine;
        this.properties = properties;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    public List<OpsCaseRecord> list(Integer limit) {
        return repository.list(limit == null ? 20 : limit);
    }

    public Map<String, Object> detail(String caseId) {
        OpsCaseRecord opsCase = repository.require(caseId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("case", opsCase);
        detail.put("evidence", repository.evidence(caseId));
        detail.put("plan", repository.latestPlan(caseId).orElse(null));
        detail.put("executions", repository.executions(caseId));
        detail.put("transitions", repository.transitions(caseId));
        return detail;
    }

    public Map<String, Object> createManualPlan(
            String caseId,
            Map<String, Object> diagnosis,
            List<OpsPlanStep> steps,
            String actor
    ) {
        OpsCaseRecord current = repository.require(caseId);
        if (current.status() != OpsCaseStatus.MANUAL_REVIEW) {
            throw new IllegalStateException("Manual plans require MANUAL_REVIEW state");
        }
        return createPlan(current, diagnosis, steps, actor, "MANUAL");
    }

    public Map<String, Object> createLlmPlan(
            OpsCaseRecord current,
            Map<String, Object> diagnosis,
            List<OpsPlanStep> steps
    ) {
        if (current.status() != OpsCaseStatus.COLLECTING_EVIDENCE) {
            throw new IllegalStateException("LLM plans require COLLECTING_EVIDENCE state");
        }
        return createPlan(current, diagnosis, steps, "ops-diagnosis-llm", "LLM");
    }

    private Map<String, Object> createPlan(
            OpsCaseRecord current,
            Map<String, Object> diagnosis,
            List<OpsPlanStep> steps,
            String actor,
            String source
    ) {
        String caseId = current.id();
        List<Map<String, Object>> evidence = repository.validEvidence(caseId);
        planValidator.validate(current, evidence, steps);
        approvalPolicyEngine.requireApproval(steps);
        String planHash = planHash(caseId, diagnosis, steps, evidence);
        OpsPlanRecord plan = repository.createPlan(
                current,
                source,
                diagnosis == null ? Map.of() : diagnosis,
                steps,
                planHash,
                actor
        );
        OpsCaseRecord planReady = repository.transition(
                current,
                OpsCaseStatus.PLAN_READY,
                actor,
                "manual plan validated",
                evidenceIds(evidence)
        );
        String approvalId = repository.requestApproval(planReady, plan, properties.getApprovalTtl());
        OpsCaseRecord awaiting = repository.transition(
                planReady,
                OpsCaseStatus.AWAITING_APPROVAL,
                actor,
                "approval requested",
                evidenceIds(evidence)
        );
        return Map.of(
                "case", awaiting,
                "plan", plan,
                "approvalId", approvalId,
                "planHash", planHash,
                "approvalExpiresAt", Instant.now().plus(properties.getApprovalTtl()).toString()
        );
    }

    public OpsCaseRecord decideApproval(
            String approvalId,
            String planHash,
            boolean approved,
            String actor
    ) {
        return repository.decideApproval(approvalId, planHash, approved, actor);
    }

    public OpsCaseRecord cancel(String caseId, String actor) {
        OpsCaseRecord current = repository.require(caseId);
        repository.requestCancellation(caseId);
        OpsCaseRecord refreshed = repository.require(caseId);
        if (refreshed.status() == OpsCaseStatus.EXECUTING
                || refreshed.status() == OpsCaseStatus.VERIFYING) {
            return refreshed;
        }
        return repository.transition(
                refreshed,
                OpsCaseStatus.CANCELLED,
                actor,
                "operator cancellation",
                List.of()
        );
    }

    public OpsCaseRecord retry(String caseId, String actor) {
        OpsCaseRecord current = repository.require(caseId);
        if (current.status() != OpsCaseStatus.MANUAL_REVIEW
                && current.status() != OpsCaseStatus.FAILED) {
            throw new IllegalStateException("Manual retry requires MANUAL_REVIEW or FAILED state");
        }
        return repository.manualRetry(current, actor);
    }

    private String planHash(
            String caseId,
            Map<String, Object> diagnosis,
            List<OpsPlanStep> steps,
            List<Map<String, Object>> evidence
    ) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("caseId", caseId);
            canonical.put("diagnosis", diagnosis == null ? Map.of() : diagnosis);
            canonical.put("steps", steps);
            canonical.put("evidence", evidence.stream()
                    .map(row -> Map.of(
                            "id", row.get("id"),
                            "hash", row.get("payload_hash"),
                            "expiresAt", row.get("expires_at")
                    ))
                    .toList());
            byte[] bytes = objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash Ops plan", error);
        }
    }

    private List<String> evidenceIds(List<Map<String, Object>> evidence) {
        return evidence.stream().map(row -> String.valueOf(row.get("id"))).toList();
    }
}
