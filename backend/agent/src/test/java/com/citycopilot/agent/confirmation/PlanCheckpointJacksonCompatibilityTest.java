package com.citycopilot.agent.confirmation;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Golden bytes were captured with the pre-upgrade Jackson 2 V1 approval codec. */
class PlanCheckpointJacksonCompatibilityTest {
    @Test void retainsV1CanonicalBytesAndPreviouslyIssuedApprovalHash() throws Exception {
        byte[] golden = resource("plan-checkpoint-v1.json");
        String oldHash = new String(resource("plan-checkpoint-v1.sha256"), StandardCharsets.UTF_8).strip();
        ObjectMapper codec = (ObjectMapper) ReflectionTestUtils.getField(PlanCheckpointIntegrity.class, "MAPPER");
        PlanCheckpoint checkpoint = codec.readValue(golden, PlanCheckpoint.class);
        assertArrayEquals(golden, codec.writeValueAsBytes(checkpoint));
        assertEquals(oldHash, PlanCheckpointIntegrity.hash(checkpoint));

        PlanCheckpoint reordered = reorder(checkpoint);
        assertArrayEquals(golden, codec.writeValueAsBytes(reordered));
        assertEquals(oldHash, PlanCheckpointIntegrity.hash(reordered));
        PlanCheckpointStep current = checkpoint.steps().get(checkpoint.currentStep());
        PendingAction persisted = new PendingAction("old-pending", current.toolName(), current.args(), "42", "old-trace",
                "旧版本审批", Instant.parse("2099-01-01T00:00:00Z"), checkpoint.planVersion(), oldHash,
                checkpoint.originalMessage(), checkpoint.hints(), checkpoint.steps(), checkpoint.currentStep(),
                checkpoint.completedOutputs(), checkpoint.evidenceIds(), checkpoint.verificationSteps());
        assertTrue(PlanCheckpointIntegrity.valid(persisted));
        assertEquals(1, persisted.planVersion());
        assertEquals("$step2.voucherId", persisted.steps().get(2).args().get("voucherId"));
    }

    private PlanCheckpoint reorder(PlanCheckpoint original) {
        Map<Integer, Object> outputs = new LinkedHashMap<>();
        original.completedOutputs().entrySet().stream().sorted(Map.Entry.<Integer, Object>comparingByKey().reversed())
                .forEach(entry -> outputs.put(entry.getKey(), reverse(entry.getValue())));
        List<PlanCheckpointStep> steps = original.steps().stream().map(step -> new PlanCheckpointStep(
                step.idx(), step.toolName(), reverseMap(step.args()), step.highRisk())).toList();
        return new PlanCheckpoint(original.planVersion(), original.originalMessage(), reverseMap(original.hints()),
                steps, original.currentStep(), outputs, original.evidenceIds(), original.verificationSteps());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reverseMap(Map<String, Object> value) { return (Map<String, Object>) reverse(value); }

    private Object reverse(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<? extends Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            Collections.reverse(entries);
            Map<Object, Object> result = new LinkedHashMap<>();
            entries.forEach(entry -> result.put(entry.getKey(), reverse(entry.getValue())));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::reverse).toList();
        return value;
    }

    private byte[] resource(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/compatibility/jackson2/" + name)) {
            assertNotNull(stream, "Missing immutable pre-upgrade fixture " + name);
            return stream.readAllBytes();
        }
    }
}
