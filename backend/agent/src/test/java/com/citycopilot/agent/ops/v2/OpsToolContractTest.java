package com.citycopilot.agent.ops.v2;

import com.citycopilot.agent.gateway.*;
import com.citycopilot.agent.tool.ToolContractSnapshot;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.contract.tool.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpsToolContractTest {
    private final OpsCaseRepository repository = mock(OpsCaseRepository.class);
    private final ToolGatewayClient gateway = mock(ToolGatewayClient.class);
    private final OpsV2Properties properties = new OpsV2Properties();
    private final JsonMapper json = JsonMapper.builderWithJackson2Defaults().build();

    @Test @SuppressWarnings("unchecked") void newPlanBindsServerContractInsteadOfCallerSuppliedVersion() {
        OpsCaseRecord current = state(OpsCaseStatus.MANUAL_REVIEW);
        when(repository.require("case")).thenReturn(current);
        when(repository.validEvidence("case")).thenReturn(List.of(Map.of("id", "evidence", "payload_hash", "hash", "expires_at", Instant.now())));
        when(gateway.refreshTools()).thenReturn(List.of(tool(2), verifier(2)));
        when(repository.createPlan(any(), anyString(), anyMap(), anyList(), anyString(), anyString()))
                .thenAnswer(call -> new OpsPlanRecord("plan", "case", 1, "MANUAL", call.getArgument(2), call.getArgument(3), call.getArgument(4), "DRAFT", Instant.now()));
        when(repository.transition(any(), any(), anyString(), anyString(), anyList())).thenReturn(current);
        when(repository.requestApproval(any(), any(), any())).thenReturn("approval");
        OpsCaseService service = new OpsCaseService(repository, mock(OpsPlanValidator.class), new ApprovalPolicyEngine(), properties, json, gateway);
        Map<String, Object> result = service.createManualPlan("case", Map.of(), List.of(new OpsPlanStep("repair_seckill_stock",
                Map.of("voucherId", 42, "expectedEvidenceHash", "hash"), "修复", new ToolContractSnapshot(99, Map.of()), new ToolContractSnapshot(99, Map.of()))), "operator");
        OpsPlanRecord plan = (OpsPlanRecord) result.get("plan");
        assertEquals(2, plan.steps().get(0).contract().contractVersion());
        assertEquals(2, plan.steps().get(0).verificationContract().contractVersion());
        assertEquals(Map.of("type", "object"), plan.steps().get(0).contract().resultSchema());
        String differentContractHash = ReflectionTestUtils.invokeMethod(service, "planHash", "case", Map.of(),
                List.of(new OpsPlanStep("repair_seckill_stock", plan.steps().get(0).arguments(), "修复", new ToolContractSnapshot(3, Map.of()), ToolContractSnapshot.of(verifier(2)))),
                repository.validEvidence("case"));
        assertNotEquals(plan.planHash(), differentContractHash);
    }

    @Test void mismatchPreservesApprovalAndMatchingExecutionCarriesReviewedVersion() {
        OpsCaseRecord approved = state(OpsCaseStatus.APPROVED);
        OpsPlanStep step = new OpsPlanStep("repair_seckill_stock", Map.of("voucherId", 42), "修复", ToolContractSnapshot.of(tool(2)), ToolContractSnapshot.of(verifier(2)));
        when(repository.latestPlan("case")).thenReturn(Optional.of(new OpsPlanRecord("plan", "case", 7, "MANUAL", Map.of(),
                List.of(step), "stored-hash", "APPROVED", Instant.now())));
        when(gateway.refreshTools()).thenReturn(List.of(tool(3), verifier(2)));
        OpsCaseWorker worker = new OpsCaseWorker(repository, null, null, gateway, properties, null, json);
        ToolExecutionException rejected = assertThrows(ToolExecutionException.class,
                () -> ReflectionTestUtils.invokeMethod(worker, "executeApprovedPlan", approved));
        assertEquals(ToolContractSnapshot.MISMATCH, rejected.code());
        when(gateway.refreshTools()).thenReturn(List.of(tool(2), verifier(3)));
        assertThrows(ToolExecutionException.class, () -> ReflectionTestUtils.invokeMethod(worker, "executeApprovedPlan", approved));
        verify(repository, never()).consumeApproval(anyString(), anyString(), anyString());
        verify(repository, never()).beginExecution(anyString(), anyString(), anyString(), anyInt(), any(), anyString());
        verify(gateway, never()).invoke(anyString(), anyMap(), any());

        when(gateway.refreshTools()).thenReturn(List.of(tool(2), verifier(2)));
        when(gateway.tools()).thenReturn(List.of(tool(2), verifier(2)));
        when(repository.consumeApproval("case", "plan", "stored-hash")).thenReturn(true);
        when(repository.transition(any(), any(), anyString(), anyString(), anyList())).thenReturn(state(OpsCaseStatus.EXECUTING));
        when(repository.beginExecution(anyString(), eq("case"), eq("plan"), eq(0), eq(step), anyString())).thenReturn(true);
        when(gateway.invoke(eq("repair_seckill_stock"), anyMap(), any())).thenReturn(ToolResult.failure("business_rejected", "rejected", false));
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(worker, "executeApprovedPlan", approved));
        ArgumentCaptor<ToolCallContext> sent = ArgumentCaptor.forClass(ToolCallContext.class);
        verify(gateway).invoke(eq("repair_seckill_stock"), eq(step.arguments()), sent.capture());
        assertEquals(2, sent.getValue().expectedVersion());
        assertEquals("ops-case:case:plan:7:step:0", sent.getValue().idempotencyKey());
        verify(repository).consumeApproval("case", "plan", "stored-hash");
    }

    @Test void verifyingResumeStillUsesTheApprovedVerifierContract() {
        OpsPlanStep step = new OpsPlanStep("repair_seckill_stock", Map.of(), "修复",
                ToolContractSnapshot.of(tool(2)), ToolContractSnapshot.of(verifier(2)));
        when(repository.latestPlan("case")).thenReturn(Optional.of(new OpsPlanRecord("plan", "case", 7, "MANUAL", Map.of(),
                List.of(step), "stored-hash", "APPROVED", Instant.now())));
        when(gateway.refreshTools()).thenReturn(List.of(tool(2), verifier(3)));
        OpsCaseWorker worker = new OpsCaseWorker(repository, null, null, gateway, properties, null, json);
        assertThrows(ToolExecutionException.class, () -> ReflectionTestUtils.invokeMethod(worker, "verify", state(OpsCaseStatus.VERIFYING)));
        verify(gateway, never()).invoke(anyString(), anyMap(), any());
        when(gateway.refreshTools()).thenReturn(List.of(tool(2), verifier(2)));
        when(gateway.invoke(eq(OpsPlanStep.VERIFICATION_TOOL), anyMap(), any()))
                .thenReturn(ToolResult.failure("unavailable", "unavailable", false));
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(worker, "verify", state(OpsCaseStatus.VERIFYING)));
        ArgumentCaptor<ToolCallContext> sent = ArgumentCaptor.forClass(ToolCallContext.class);
        verify(gateway).invoke(eq(OpsPlanStep.VERIFICATION_TOOL), anyMap(), sent.capture());
        assertEquals(2, sent.getValue().expectedVersion());

        // Core can change after the catalog read. Its stable mismatch must still stop automatic verification retries.
        when(gateway.invoke(eq(OpsPlanStep.VERIFICATION_TOOL), anyMap(), any()))
                .thenReturn(ToolResult.failure(ToolContractSnapshot.MISMATCH, "contract changed", false));
        ToolExecutionException changed = assertThrows(ToolExecutionException.class,
                () -> ReflectionTestUtils.invokeMethod(worker, "verify", state(OpsCaseStatus.VERIFYING)));
        assertEquals(ToolContractSnapshot.MISMATCH, changed.code());
        assertFalse(changed.retryable());
        when(repository.require("case")).thenReturn(state(OpsCaseStatus.VERIFYING));
        ReflectionTestUtils.invokeMethod(worker, "handleFailure", "case", changed);
        verify(repository).transition(any(), eq(OpsCaseStatus.MANUAL_REVIEW), eq("ops-worker"), eq("contract changed"), eq(List.of()));
        verify(repository, never()).scheduleRetry(any(), anyString(), any());
    }

    private ToolMetadata tool(int version) {
        return ToolMetadata.fromDefinition(new ToolDefinition("repair_seckill_stock", "repair", List.of(), 60, false, List.of(),
                ToolAudience.OPS, ToolSideEffect.NON_IDEMPOTENT_WRITE, ToolRiskLevel.HIGH, ToolApprovalPolicy.NONE, "core", version, Map.of("type", "object")));
    }
    private ToolMetadata verifier(int version) {
        return ToolMetadata.fromDefinition(new ToolDefinition(OpsPlanStep.VERIFICATION_TOOL, "verify", List.of(), 60, true, List.of(),
                ToolAudience.OPS, ToolSideEffect.NONE, ToolRiskLevel.LOW, ToolApprovalPolicy.NONE, "core", version, Map.of("type", "object")));
    }
    private OpsCaseRecord state(OpsCaseStatus status) {
        return new OpsCaseRecord("case", "seckill:42", "event", "stock", status, 1, 0, 0, false, null, Instant.now(), Instant.now());
    }
}
