package com.citycopilot.agent.ops.v2;

import com.citycopilot.agent.config.AgentAiProperties;
import com.citycopilot.agent.budget.AgentBudget;
import com.citycopilot.agent.executor.AgentLlmClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 使用大模型生成结构化 Ops 诊断候选计划的实现。
 *
 * <p>模型只负责从现有证据提出候选项，输出仍须经过 {@link OpsPlanValidator} 和审批策略；模型关闭、证据不足或 JSON 无效时安全移交人工。</p>
 */
@Component
public class StructuredLlmOpsDiagnosisPlanner implements OpsDiagnosisPlanner {
    /** 执行无工具诊断补全的模型客户端。 */
    private final AgentLlmClient llmClient;
    /** 用于判断远程模型是否具备有效配置。 */
    private final AgentAiProperties aiProperties;
    /** 序列化证据并解析结构化模型响应。 */
    private final ObjectMapper objectMapper;

    public StructuredLlmOpsDiagnosisPlanner(
            AgentLlmClient llmClient,
            AgentAiProperties aiProperties,
            ObjectMapper objectMapper
    ) {
        this.llmClient = llmClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 请求模型生成恰好一个绑定证据的库存修复步骤。
     *
     * @return 解析成功的候选计划；人工移交、模型禁用或协议异常时为空
     */
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
        // 提示只允许复制新鲜证据中的标识与哈希，禁止模型自造修复目标。
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
            if (AgentBudget.current() != null) AgentBudget.current().plan(steps.size());
            return Optional.of(new DiagnosisPlan(diagnosis, steps));
        } catch (Exception invalidProviderOutput) {
            AgentBudget.rethrowIfStopped(invalidProviderOutput);
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
