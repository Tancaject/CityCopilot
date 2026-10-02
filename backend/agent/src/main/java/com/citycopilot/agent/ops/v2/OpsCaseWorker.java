package com.citycopilot.agent.ops.v2;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.budget.AgentBudget;
import com.citycopilot.agent.budget.AgentBudgetException;
import com.citycopilot.agent.budget.AgentExecutionRuntime;
import com.citycopilot.agent.gateway.ToolGatewayClient;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.gateway.ToolExecutionException;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolContractSnapshot;
import com.citycopilot.contract.security.PermissionCodes;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Ops Case v2 状态机的后台推进器。
 *
 * <p>分离只读证据线程池与单独的写执行池，使用 Redis 加数据库双租约防止多实例并发处理；写入必须消费一次性审批和幂等执行记录，完成后再用独立只读工具验证业务不变量。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.ops-v2",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OpsCaseWorker {
    /** 证据采集和验证所需的最小只读权限。 */
    private static final Set<String> READER_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ
    );
    /** 获批修复工具额外需要的订单写权限。 */
    private static final Set<String> EXECUTOR_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ,
            PermissionCodes.ORDER_WRITE
    );

    /** 持久化状态机、租约、证据、审批和执行围栏。 */
    private final OpsCaseRepository repository;
    /** 校验并创建模型候选计划。 */
    private final OpsCaseService caseService;
    /** 基于新鲜证据生成结构化候选计划。 */
    private final OpsDiagnosisPlanner diagnosisPlanner;
    /** 所有只读与写工具的统一远程入口。 */
    private final ToolGatewayClient toolGateway;
    /** worker 标识、TTL、租约和线程数配置。 */
    private final OpsV2Properties properties;
    /** 提供跨实例快速租约的 Redis 客户端。 */
    private final StringRedisTemplate redisTemplate;
    /** 序列化并哈希证据快照。 */
    private final ObjectMapper objectMapper;
    /** 隔离高并发只读证据采集的线程池。 */
    private ExecutorService readerPool;
    /** 限制高风险写与验证并发度的线程池。 */
    private ExecutorService executorPool;
    /** 可选的 case 结果指标注册器。 */
    private MeterRegistry meterRegistry;
    private AgentExecutionRuntime runtime = AgentExecutionRuntime.directUse();

    @Autowired
    void configureRuntime(AgentExecutionRuntime runtime) { this.runtime = runtime; }

    public OpsCaseWorker(
            OpsCaseRepository repository,
            OpsCaseService caseService,
            OpsDiagnosisPlanner diagnosisPlanner,
            ToolGatewayClient toolGateway,
            OpsV2Properties properties,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.caseService = caseService;
        this.diagnosisPlanner = diagnosisPlanner;
        this.toolGateway = toolGateway;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        meterRegistry = registry;
    }

    /** 按配置创建守护线程池，读写任务使用不同容量。 */
    @PostConstruct
    void start() {
        readerPool = boundedPool(Math.max(1, properties.getReaderThreads()), "ops-reader");
        executorPool = boundedPool(Math.max(1, properties.getExecutorThreads()), "ops-executor");
    }

    /**
     * 扫描可推进 case 并按状态分派到读池或执行池。
     */
    @Scheduled(fixedDelayString = "${citycopilot.ops-v2.poll-delay-ms:2000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        for (OpsCaseRecord candidate : repository.workCandidates(20)) {
            ExecutorService pool = candidate.status() == OpsCaseStatus.OPEN
                    ? readerPool
                    : executorPool;
            AgentBudget budget = runtime.newBudget();
            try {
                pool.submit(() -> {
                    try {
                        runtime.runInline(budget, () -> { processWithLease(candidate.id()); return null; });
                    } catch (AgentBudgetException exhausted) {
                        // No approval or lease is consumed until admission succeeds; the next poll may retry.
                        count(exhausted.code());
                    }
                });
            } catch (RejectedExecutionException full) {
                runtime.release(budget);
                count("queue_full");
            }
        }
    }

    /**
     * 在 Redis 快速租约和数据库权威租约均成功后推进一个 case。
     *
     * <p>双层租约减少跨实例竞争；状态更新仍依赖数据库乐观锁作为最终并发边界。</p>
     */
    private void processWithLease(String caseId) {
        AgentBudget.checkCurrent();
        String leaseKey = "ops:case:lease:" + caseId;
        String owner = properties.getWorkerId() + ":" + Thread.currentThread().getName();
        Boolean redisLease = redisTemplate.opsForValue().setIfAbsent(
                leaseKey,
                owner,
                properties.getLeaseDuration()
        );
        if (!Boolean.TRUE.equals(redisLease)) {
            return;
        }
        try {
            // Redis 只做快速排他，数据库租约才与持久化 case 同源并提供第二道围栏。
            if (!repository.tryAcquireLease(caseId, owner, properties.getLeaseDuration())) {
                return;
            }
            OpsCaseRecord current = repository.require(caseId);
            switch (current.status()) {
                case OPEN -> collectEvidence(current);
                case APPROVED -> executeApprovedPlan(current);
                case VERIFYING -> verify(current);
                case RETRY_WAIT -> resumeRetry(current);
                default -> {
                }
            }
        } catch (OpsCaseRepository.OptimisticCaseConflictException concurrentWorkerWon) {
            count("optimistic_conflict");
        } catch (Exception error) {
            handleFailure(caseId, error);
        } finally {
            repository.releaseLease(caseId, owner);
            // 仅删除仍属于自己的 Redis 租约，避免误删已经过期并被其他实例接管的新租约。
            String currentOwner = redisTemplate.opsForValue().get(leaseKey);
            if (owner.equals(currentOwner)) {
                redisTemplate.delete(leaseKey);
            }
        }
    }

    /** 从 OPEN 进入证据采集，并在任何工具调用前尊重取消请求。 */
    private void collectEvidence(OpsCaseRecord open) {
        if (open.cancellationRequested()) {
            repository.transition(open, OpsCaseStatus.CANCELLED, "ops-worker", "cancelled before collection", List.of());
            return;
        }
        OpsCaseRecord collecting = repository.transition(
                open,
                OpsCaseStatus.COLLECTING_EVIDENCE,
                "ops-reader",
                "collecting fresh consistency evidence",
                List.of()
        );
        collectEvidenceNow(collecting);
    }

    /**
     * 调用只读一致性快照工具、保存限时证据，再尝试生成并提交候选计划。
     */
    private void collectEvidenceNow(OpsCaseRecord collecting) {
        long voucherId = voucherId(collecting.incidentKey());
        ToolResult result = toolGateway.invoke(
                "get_seckill_consistency_snapshot",
                Map.of("voucherId", voucherId),
                readerContext()
        );
        if (!result.ok()) {
            throw new IllegalStateException("Evidence collection failed: " + result.error());
        }
        String payloadJson = json(result.data());
        String evidenceId = repository.saveEvidence(
                collecting.id(),
                "SECKILL_CONSISTENCY_SNAPSHOT",
                "core:get_seckill_consistency_snapshot",
                payloadJson,
                sha256(payloadJson),
                Instant.now(),
                properties.getEvidenceTtl()
        );
        List<Map<String, Object>> evidence = repository.validEvidence(collecting.id());
        // 模型只提出候选计划；空结果立即转人工，不从事件或默认值猜测 voucherId。
        Optional<OpsDiagnosisPlanner.DiagnosisPlan> generated = diagnosisPlanner.plan(collecting, evidence);
        if (generated.isEmpty()) {
            repository.transition(
                    repository.require(collecting.id()),
                    OpsCaseStatus.MANUAL_REVIEW,
                    "ops-reader",
                    "LLM unavailable or structured plan invalid; manual diagnosis required",
                    List.of(evidenceId)
            );
            count("manual_handoff");
            return;
        }
        OpsDiagnosisPlanner.DiagnosisPlan plan = generated.get();
        caseService.createLlmPlan(
                repository.require(collecting.id()),
                plan.diagnosis(),
                plan.steps()
        );
        count("plan_ready");
    }

    /**
     * 一次性消费审批，登记幂等执行记录并调用受限写工具。
     *
     * <p>写工具返回失败后不自动重放，而是由统一失败处理转人工复核，避免未知写入结果造成重复修复。</p>
     */
    private void executeApprovedPlan(OpsCaseRecord approved) {
        if (approved.cancellationRequested()) {
            repository.transition(approved, OpsCaseStatus.CANCELLED, "ops-executor", "cancelled before execution", List.of());
            return;
        }
        OpsPlanRecord plan = repository.latestPlan(approved.id())
                .orElseThrow(() -> new IllegalStateException("Approved Case has no plan"));
        if (AgentBudget.current() != null) {
            AgentBudget.current().context(plan);
            AgentBudget.current().plan(plan.steps().size() + 1);
            AgentBudget.current().check();
        }
        List<ToolMetadata> catalog = toolGateway.refreshTools();
        for (OpsPlanStep step : plan.steps()) {
            ToolContractSnapshot.requireCompatible(step.toolName(), step.contract(), catalog);
            requireVerificationContract(step, catalog);
        }
        // 审批必须与最新 planId/hash 匹配、未过期且从未消费。
        if (!repository.consumeApproval(approved.id(), plan.id(), plan.planHash())) {
            throw new IllegalStateException("Approval missing, expired, stale, or already consumed");
        }
        OpsCaseRecord executing = repository.transition(
                approved,
                OpsCaseStatus.EXECUTING,
                "ops-executor",
                "one-time approval consumed",
                List.of()
        );
        for (int index = 0; index < plan.steps().size(); index++) {
            AgentBudget.checkCurrent();
            OpsPlanStep step = plan.steps().get(index);
            ToolContractSnapshot.requireCompatible(step.toolName(), step.contract(), toolGateway.tools());
            String idempotencyKey = "ops-case:" + executing.id() + ":plan:" + plan.version() + ":step:" + index;
            String executionId = UUID.randomUUID().toString();
            // 数据库先登记幂等键；已存在的步骤不再调用写工具。
            if (!repository.beginExecution(
                    executionId,
                    executing.id(),
                    plan.id(),
                    index,
                    step,
                    idempotencyKey
            )) {
                continue;
            }
            // 幂等键随 ToolCallContext 传给 Core，使跨服务重试也落在同一业务去重边界。
            ToolResult result = toolGateway.invoke(
                    step.toolName(),
                    step.arguments(),
                    executorContext().withIdempotencyKey(idempotencyKey)
                            .withExpectedVersion(ToolContractSnapshot.expectedVersion(step.contract()))
            );
            repository.finishExecution(
                    executionId,
                    result.ok(),
                    result.ok() ? result.data() : result.error(),
                    result.ok() || result.error() == null ? null : result.error().code()
            );
            if (!result.ok()) {
                rejectContractMismatch(result);
                throw new IllegalStateException("Ops write tool failed: " + result.error());
            }
        }
        OpsCaseRecord verifying = repository.transition(
                repository.require(executing.id()),
                OpsCaseStatus.VERIFYING,
                "ops-executor",
                "write steps complete; independent verification required",
                List.of()
        );
        verify(verifying);
    }

    private void rejectContractMismatch(ToolResult result) {
        if (result.error() != null && ToolContractSnapshot.MISMATCH.equals(result.error().code())) {
            throw new ToolExecutionException(result.error().code(), result.error().message(), false);
        }
    }

    private void requireVerificationContract(OpsPlanStep step, List<ToolMetadata> catalog) {
        if (step.contract() != null && step.verificationContract() == null) {
            throw new ToolExecutionException(ToolContractSnapshot.MISMATCH, "审批缺少复核工具契约，请重新审阅计划。", false);
        }
        ToolContractSnapshot.requireCompatible(OpsPlanStep.VERIFICATION_TOOL, step.verificationContract(), catalog);
    }

    /**
     * 用独立只读工具采集修复后证据，仅明确 {@code verified=true} 才进入成功终态。
     */
    private void verify(OpsCaseRecord verifying) {
        OpsPlanRecord plan = repository.latestPlan(verifying.id())
                .orElseThrow(() -> new IllegalStateException("Verification requires the approved plan"));
        if (plan.steps().size() != 1) throw new IllegalStateException("Ops verification requires one approved repair step");
        OpsPlanStep step = plan.steps().get(0);
        requireVerificationContract(step, toolGateway.refreshTools());
        long voucherId = voucherId(verifying.incidentKey());
        ToolResult result = toolGateway.invoke(
                OpsPlanStep.VERIFICATION_TOOL,
                Map.of("voucherId", voucherId),
                readerContext().withExpectedVersion(ToolContractSnapshot.expectedVersion(step.verificationContract()))
        );
        if (!result.ok()) {
            rejectContractMismatch(result);
            throw new IllegalStateException("Verifier tool failed: " + result.error());
        }
        String payloadJson = json(result.data());
        String evidenceId = repository.saveEvidence(
                verifying.id(),
                "POST_REPAIR_VERIFICATION",
                "core:verify_seckill_consistency",
                payloadJson,
                sha256(payloadJson),
                Instant.now(),
                properties.getEvidenceTtl()
        );
        boolean verified = result.data() instanceof Map<?, ?> data
                && Boolean.TRUE.equals(data.get("verified"));
        OpsCaseRecord refreshed = repository.require(verifying.id());
        if (refreshed.cancellationRequested()) {
            // 原子写已完成后不能假装取消回滚，保留验证证据并交给人工判断最终结果。
            repository.transition(
                    refreshed,
                    OpsCaseStatus.MANUAL_REVIEW,
                    "ops-verifier",
                    "cancellation requested after atomic execution; result retained for review",
                    List.of(evidenceId)
            );
            return;
        }
        repository.transition(
                refreshed,
                verified ? OpsCaseStatus.SUCCEEDED : OpsCaseStatus.MANUAL_REVIEW,
                "ops-verifier",
                verified ? "fresh evidence confirms business invariant" : "postcondition failed",
                List.of(evidenceId)
        );
        count(verified ? "succeeded" : "verification_failed");
    }

    /** 根据保存的 currentStep 恢复证据采集或独立验证，而不重复获批写步骤。 */
    private void resumeRetry(OpsCaseRecord retry) {
        if (retry.cancellationRequested()) {
            repository.transition(retry, OpsCaseStatus.CANCELLED, "ops-worker", "cancelled during retry wait", List.of());
            return;
        }
        OpsCaseStatus target = retry.currentStep() == 2
                ? OpsCaseStatus.VERIFYING
                : OpsCaseStatus.COLLECTING_EVIDENCE;
        OpsCaseRecord resumed = repository.transition(
                retry,
                target,
                "ops-worker",
                "retry checkpoint resumed",
                List.of()
        );
        if (target == OpsCaseStatus.VERIFYING) {
            verify(resumed);
        } else {
            collectEvidenceNow(resumed);
        }
    }

    /**
     * 按失败阶段决定人工复核、有限退避重试或死信。
     *
     * <p>EXECUTING 阶段的结果可能不明确，始终转人工；只读/验证阶段最多自动重试两次。</p>
     */
    private void handleFailure(String caseId, Exception error) {
        // Cancellation interrupts I/O; clear the flag before persisting the terminal/manual-review state.
        if (error instanceof AgentBudgetException) Thread.interrupted();
        try {
            OpsCaseRecord current = repository.require(caseId);
            if (current.status() == OpsCaseStatus.SUCCEEDED
                    || current.status() == OpsCaseStatus.CANCELLED
                    || current.status() == OpsCaseStatus.MANUAL_REVIEW) {
                return;
            }
            if (error instanceof ToolExecutionException changed && ToolContractSnapshot.MISMATCH.equals(changed.code())) {
                repository.transition(current, OpsCaseStatus.MANUAL_REVIEW, "ops-worker",
                        changed.getMessage(), List.of());
                count("contract_manual_review");
                return;
            }
            if (error instanceof AgentBudgetException && current.status() != OpsCaseStatus.EXECUTING) {
                repository.transition(current, OpsCaseStatus.MANUAL_REVIEW, "ops-worker",
                        "request budget stopped work: " + error.getMessage(), List.of());
                count("budget_manual_review");
                return;
            }
            if (current.status() == OpsCaseStatus.EXECUTING) {
                repository.deadLetter(
                        "OPS_CASE_EXECUTION",
                        current.id(),
                        Map.of("status", current.status().name(), "incidentKey", current.incidentKey()),
                        error.getMessage(),
                        current.retryCount()
                );
                repository.transition(
                        current,
                        OpsCaseStatus.MANUAL_REVIEW,
                        "ops-worker",
                        "write outcome requires operator review: " + error.getMessage(),
                        List.of()
                );
                count("execution_manual_review");
                return;
            }
            if (current.retryCount() >= 2) {
                repository.deadLetter(
                        "OPS_CASE",
                        current.id(),
                        Map.of("status", current.status().name(), "incidentKey", current.incidentKey()),
                        error.getMessage(),
                        current.retryCount()
                );
                repository.transition(
                        current,
                        OpsCaseStatus.MANUAL_REVIEW,
                        "ops-worker",
                        "retry budget exhausted: " + error.getMessage(),
                        List.of()
                );
                count("retry_exhausted");
                return;
            }
            repository.scheduleRetry(current, error.getMessage(), Duration.ofSeconds(5L * (current.retryCount() + 1)));
            count("retry_scheduled");
        } catch (Exception ignored) {
            count("failure_persist_error");
        }
    }

    /** @return 不含写权限的 Ops 只读服务身份 */
    private ToolCallContext readerContext() {
        return new ToolCallContext("ops-reader", READER_PERMISSIONS);
    }

    /** @return 仅供已审批步骤使用的 Ops 写服务身份 */
    private ToolCallContext executorContext() {
        return new ToolCallContext("ops-executor", EXECUTOR_PERMISSIONS);
    }

    /** 从版本化事故键末段解析 voucherId，格式不符时拒绝继续。 */
    private long voucherId(String incidentKey) {
        int separator = incidentKey.lastIndexOf(':');
        if (separator < 0 || separator == incidentKey.length() - 1) {
            throw new IllegalArgumentException("Invalid stock discrepancy incidentKey");
        }
        return Long.parseLong(incidentKey.substring(separator + 1));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Ops evidence", error);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash Ops evidence", error);
        }
    }

    private Thread daemonThread(Runnable runnable, String prefix) {
        Thread thread = new Thread(runnable, prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        thread.setDaemon(true);
        return thread;
    }

    private ExecutorService boundedPool(int threads, String prefix) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(runtime.limits().getQueueCapacity()),
                task -> daemonThread(task, prefix), new ThreadPoolExecutor.AbortPolicy());
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            Counter.builder("citycopilot.ops.case.outcome")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /** 容器关闭时有序停止读写线程池。 */
    @PreDestroy
    void close() {
        shutdown(readerPool);
        shutdown(executorPool);
    }

    private void shutdown(ExecutorService pool) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
