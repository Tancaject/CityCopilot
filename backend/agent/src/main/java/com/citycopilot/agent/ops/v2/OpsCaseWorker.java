package com.citycopilot.agent.ops.v2;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGatewayClient;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.contract.security.PermissionCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        prefix = "citycopilot.ops-v2",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OpsCaseWorker {
    private static final Set<String> READER_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ
    );
    private static final Set<String> EXECUTOR_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ,
            PermissionCodes.ORDER_WRITE
    );

    private final OpsCaseRepository repository;
    private final OpsCaseService caseService;
    private final OpsDiagnosisPlanner diagnosisPlanner;
    private final ToolGatewayClient toolGateway;
    private final OpsV2Properties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private ExecutorService readerPool;
    private ExecutorService executorPool;
    private MeterRegistry meterRegistry;

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
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        meterRegistry = registry;
    }

    @PostConstruct
    void start() {
        readerPool = Executors.newFixedThreadPool(
                Math.max(1, properties.getReaderThreads()),
                runnable -> daemonThread(runnable, "ops-reader")
        );
        executorPool = Executors.newFixedThreadPool(
                Math.max(1, properties.getExecutorThreads()),
                runnable -> daemonThread(runnable, "ops-executor")
        );
    }

    @Scheduled(fixedDelayString = "${citycopilot.ops-v2.poll-delay-ms:2000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        for (OpsCaseRecord candidate : repository.workCandidates(20)) {
            ExecutorService pool = candidate.status() == OpsCaseStatus.OPEN
                    ? readerPool
                    : executorPool;
            pool.submit(() -> processWithLease(candidate.id()));
        }
    }

    private void processWithLease(String caseId) {
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
            String currentOwner = redisTemplate.opsForValue().get(leaseKey);
            if (owner.equals(currentOwner)) {
                redisTemplate.delete(leaseKey);
            }
        }
    }

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

    private void executeApprovedPlan(OpsCaseRecord approved) {
        if (approved.cancellationRequested()) {
            repository.transition(approved, OpsCaseStatus.CANCELLED, "ops-executor", "cancelled before execution", List.of());
            return;
        }
        OpsPlanRecord plan = repository.latestPlan(approved.id())
                .orElseThrow(() -> new IllegalStateException("Approved Case has no plan"));
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
            OpsPlanStep step = plan.steps().get(index);
            String idempotencyKey = "ops-case:" + executing.id() + ":plan:" + plan.version() + ":step:" + index;
            String executionId = UUID.randomUUID().toString();
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
            ToolResult result = toolGateway.invoke(
                    step.toolName(),
                    step.arguments(),
                    executorContext().withIdempotencyKey(idempotencyKey)
            );
            repository.finishExecution(
                    executionId,
                    result.ok(),
                    result.ok() ? result.data() : result.error(),
                    result.ok() || result.error() == null ? null : result.error().code()
            );
            if (!result.ok()) {
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

    private void verify(OpsCaseRecord verifying) {
        long voucherId = voucherId(verifying.incidentKey());
        ToolResult result = toolGateway.invoke(
                "verify_seckill_consistency",
                Map.of("voucherId", voucherId),
                readerContext()
        );
        if (!result.ok()) {
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

    private void handleFailure(String caseId, Exception error) {
        try {
            OpsCaseRecord current = repository.require(caseId);
            if (current.status() == OpsCaseStatus.SUCCEEDED
                    || current.status() == OpsCaseStatus.CANCELLED
                    || current.status() == OpsCaseStatus.MANUAL_REVIEW) {
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

    private ToolCallContext readerContext() {
        return new ToolCallContext("ops-reader", READER_PERMISSIONS);
    }

    private ToolCallContext executorContext() {
        return new ToolCallContext("ops-executor", EXECUTOR_PERMISSIONS);
    }

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

    private void count(String outcome) {
        if (meterRegistry != null) {
            Counter.builder("citycopilot.ops.case.outcome")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

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
