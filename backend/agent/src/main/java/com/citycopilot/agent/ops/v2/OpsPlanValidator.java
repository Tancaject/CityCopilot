package com.citycopilot.agent.ops.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OpsPlanValidator {
    private static final Set<String> ALLOWED_WRITE_TOOLS = Set.of("repair_seckill_stock");
    private final ObjectMapper objectMapper;

    public OpsPlanValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    public void validate(
            OpsCaseRecord opsCase,
            List<Map<String, Object>> validEvidence,
            List<OpsPlanStep> steps
    ) {
        if (opsCase.status() != OpsCaseStatus.MANUAL_REVIEW
                && opsCase.status() != OpsCaseStatus.COLLECTING_EVIDENCE) {
            throw new IllegalStateException("A plan requires evidence collection or manual review state");
        }
        if (validEvidence.isEmpty()) {
            throw new IllegalStateException("A plan requires unexpired evidence");
        }
        if (steps == null || steps.size() != 1) {
            throw new IllegalArgumentException("The stock repair playbook requires exactly one bounded write step");
        }
        OpsPlanStep step = steps.get(0);
        if (!ALLOWED_WRITE_TOOLS.contains(step.toolName())) {
            throw new IllegalArgumentException("Unknown or disallowed Ops write tool: " + step.toolName());
        }
        Object voucherId = step.arguments().get("voucherId");
        Object expectedEvidenceHash = step.arguments().get("expectedEvidenceHash");
        if (!(voucherId instanceof Number) || expectedEvidenceHash == null) {
            throw new IllegalArgumentException("repair_seckill_stock requires voucherId and expectedEvidenceHash");
        }
        boolean evidenceMatches = validEvidence.stream().anyMatch(row -> {
            try {
                Map<String, Object> payload = objectMapper.readValue(
                        String.valueOf(row.get("payload_json")),
                        new TypeReference<>() {
                        }
                );
                return expectedEvidenceHash.equals(payload.get("evidenceHash"))
                        && ((Number) voucherId).longValue()
                        == ((Number) payload.get("voucherId")).longValue();
            } catch (Exception ignored) {
                return false;
            }
        });
        if (!evidenceMatches) {
            throw new IllegalStateException("The plan is not bound to current unexpired evidence");
        }
    }
}
