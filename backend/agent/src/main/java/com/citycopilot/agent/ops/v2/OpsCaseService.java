package com.citycopilot.agent.ops.v2;

import tools.jackson.databind.ObjectMapper;
import com.citycopilot.agent.gateway.ToolGatewayClient;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolContractSnapshot;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ops Case v2 的应用服务。
 *
 * <p>组合确定性计划校验、集中审批策略与仓储状态机，生成绑定新鲜证据的计划哈希，并提供人工取消和重试入口。</p>
 */
@Service
public class OpsCaseService {
    /** case、证据、计划、审批和时间线的事务仓储。 */
    private final OpsCaseRepository repository;
    /** 校验步骤与当前证据严格绑定。 */
    private final OpsPlanValidator planValidator;
    /** 限定可进入审批的写工具。 */
    private final ApprovalPolicyEngine approvalPolicyEngine;
    /** 审批有效期等 v2 运行配置。 */
    private final OpsV2Properties properties;
    /** 生成稳定计划哈希的规范化序列化器。 */
    private final ObjectMapper objectMapper;
    private final ToolGatewayClient toolGateway;

    public OpsCaseService(
            OpsCaseRepository repository,
            OpsPlanValidator planValidator,
            ApprovalPolicyEngine approvalPolicyEngine,
            OpsV2Properties properties,
            ObjectMapper objectMapper,
            ToolGatewayClient toolGateway
    ) {
        this.repository = repository;
        this.planValidator = planValidator;
        this.approvalPolicyEngine = approvalPolicyEngine;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.toolGateway = toolGateway;
    }

    /** @return 最近 case，空 limit 使用服务默认值 */
    public List<OpsCaseRecord> list(Integer limit) {
        return repository.list(limit == null ? 20 : limit);
    }

    /**
     * 聚合一个 case 的事实、计划和审计时间线。
     */
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

    /**
     * 在人工复核状态提交计划，并进入等待审批状态。
     */
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

    /**
     * 接受自动诊断器候选计划；仅证据采集状态可进入此路径。
     */
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

    /** 校验候选计划、固化哈希、创建限时审批并推进状态机。 */
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
        List<ToolMetadata> catalog = toolGateway.refreshTools();
        // Bind server metadata, never a contract/version supplied by the model or manual-plan JSON.
        ToolContractSnapshot verification = ToolContractSnapshot.of(catalog.stream()
                .filter(tool -> tool.name().equals(OpsPlanStep.VERIFICATION_TOOL)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing Ops verification tool")));
        steps = steps.stream().map(step -> new OpsPlanStep(step.toolName(), step.arguments(), step.purpose(),
                ToolContractSnapshot.of(catalog.stream().filter(tool -> tool.name().equals(step.toolName()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + step.toolName()))), verification))
                .toList();
        // 哈希同时覆盖 case、诊断、步骤和证据摘要，审批后任一部分变化都会失配。
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

    /** 将审批决定委托给仓储，以数据库条件更新保证一次性和哈希匹配。 */
    public OpsCaseRecord decideApproval(
            String approvalId,
            String planHash,
            boolean approved,
            String actor
    ) {
        return repository.decideApproval(approvalId, planHash, approved, actor);
    }

    /**
     * 标记取消请求；非执行态立即转为 CANCELLED，执行/验证态由 worker 在安全点观察。
     */
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

    /** 手工重试仅允许从 MANUAL_REVIEW 或 FAILED 状态发起。 */
    public OpsCaseRecord retry(String caseId, String actor) {
        OpsCaseRecord current = repository.require(caseId);
        if (current.status() != OpsCaseStatus.MANUAL_REVIEW
                && current.status() != OpsCaseStatus.FAILED) {
            throw new IllegalStateException("Manual retry requires MANUAL_REVIEW or FAILED state");
        }
        return repository.manualRetry(current, actor);
    }

    /** 计算审批绑定使用的 SHA-256 计划摘要。 */
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
