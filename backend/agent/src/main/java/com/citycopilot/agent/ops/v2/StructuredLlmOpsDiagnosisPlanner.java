package com.citycopilot.agent.ops.v2;

import com.citycopilot.agent.config.AgentAiProperties;
import com.citycopilot.agent.executor.AgentLlmClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class StructuredLlmOpsDiagnosisPlanner implements OpsDiagnosisPlanner {
    private final AgentLlmClient llmClient;
    private final AgentAiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public StructuredLlmOpsDiagnosisPlanner(
            AgentLlmClient llmClient,
            AgentAiProperties aiProperties,
            ObjectMapper objectMapper
    ) {
        this.llmClient = llmClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public Optional<DiagnosisPlan> plan(
            OpsCaseRecord opsCase,
            List<Map<String, Object>> evidence
    ) {
        if (!Boolean.TRUE.equals(aiProperties.getEnabled())
                || aiProperties.getApiKey() == null
                || aiProperties.getApiKey().isBlank()) {
            return Optional.empty();
        }
        String answer = llmClient.complete(
                """
                You are the CityCopilot Ops diagnosis planner.
                Return JSON only with this exact structure:
                {"diagnosis":{"summary":"...","rootCause":"...","confidence":0.0},
                 "steps":[{"toolName":"repair_seckill_stock",
                            "arguments":{"voucherId":1,"expectedEvidenceHash":"..."},
                            "purpose":"..."}]}
                Use only facts from the supplied evidence. Never invent identifiers.
                Use exactly one repair_seckill_stock step and copy voucherId and evidenceHash
                from fresh consistency evidence. If evidence is insufficient, return
                {"manualHandoff":true,"reason":"..."} instead.
                """,
                List.of(json(Map.of("case", opsCase, "evidence", evidence)))
        );
        try {
            Map<String, Object> parsed = objectMapper.readValue(stripFence(answer), new TypeReference<>() {
            });
            if (Boolean.TRUE.equals(parsed.get("manualHandoff"))) {
                return Optional.empty();
            }
            Map<String, Object> diagnosis = objectMapper.convertValue(
                    parsed.get("diagnosis"),
                    new TypeReference<>() {
                    }
            );
            List<OpsPlanStep> steps = objectMapper.convertValue(
                    parsed.get("steps"),
                    new TypeReference<>() {
                    }
            );
            return Optional.of(new DiagnosisPlan(diagnosis, steps));
        } catch (Exception invalidProviderOutput) {
            return Optional.empty();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Ops evidence for LLM", error);
        }
    }

    private String stripFence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
