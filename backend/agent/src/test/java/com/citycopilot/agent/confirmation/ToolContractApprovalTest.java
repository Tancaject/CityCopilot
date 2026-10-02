package com.citycopilot.agent.confirmation;

import com.citycopilot.agent.executor.*;
import com.citycopilot.agent.gateway.*;
import com.citycopilot.agent.http.AgentController;
import com.citycopilot.agent.security.AgentRequestContext;
import com.citycopilot.agent.security.AgentRequestContextHolder;
import com.citycopilot.agent.tool.*;
import com.citycopilot.agent.trace.InMemoryTraceStore;
import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolContractApprovalTest {
    private final ToolCallContext user = new ToolCallContext("42", Set.of("*"));
    private final InMemoryPendingActionStore store = new InMemoryPendingActionStore();
    private final Catalog gateway = new Catalog();
    private final InMemoryTraceStore traces = new InMemoryTraceStore();
    private final PlanExecuteExecutor executor = new PlanExecuteExecutor(gateway, traces);
    private final AgentController controller = new AgentController(null, executor, null, traces, store, gateway, null, null);

    @AfterEach void clear() { AgentRequestContextHolder.clear(); }

    @Test void changedVersionSchemaOrVerifierNeverConsumesApproval() {
        PendingAction pending = create(false);
        gateway.catalog = List.of(tool("write", 3, Map.of("type", "object")), tool("verify", 2, Map.of()));
        rejectWithoutConsuming(pending);
        gateway.catalog = List.of(tool("write", 2, Map.of("type", "string")), tool("verify", 2, Map.of()));
        rejectWithoutConsuming(pending);
        gateway.catalog = List.of(tool("write", 2, Map.of("type", "object")), tool("verify", 3, Map.of()));
        rejectWithoutConsuming(pending);
        gateway.unavailable = true;
        rejectWithoutConsuming(pending);
        assertTrue(gateway.calls.isEmpty());
    }

    @Test void compatibleApprovalCarriesReviewedVersionsAndOriginalIdempotencyKey() {
        PendingAction pending = create(false);
        login();
        assertEquals(true, controller.confirm(pending.pendingId()).get("ok"));
        assertTrue(store.find(pending.pendingId()).isEmpty());
        assertEquals(List.of("write", "verify"), gateway.names);
        assertEquals(2, gateway.calls.get(0).expectedVersion());
        assertEquals(2, gateway.calls.get(1).expectedVersion());
        assertEquals(pending.pendingId() + ":plan:1", gateway.calls.get(0).idempotencyKey());
    }

    @Test void legacyApprovalMeansVersionOneAndDoesNotInventAnOldSchema() {
        PendingAction pending = create(true);
        assertNull(pending.toolContracts());
        rejectWithoutConsuming(pending);
        gateway.catalog = List.of(tool("write", 1, Map.of("type", "string")), tool("verify", 1, Map.of()));
        login();
        assertEquals(true, controller.confirm(pending.pendingId()).get("ok"));
        assertEquals(1, gateway.calls.get(0).expectedVersion());
    }

    @Test void newPlanningBindsServerMetadataAndPersistsItWithTheApprovalHash() {
        AgentLlmClient model = mock(AgentLlmClient.class);
        when(model.complete(anyString(), anyList())).thenReturn(
                "{\"steps\":[{\"idx\":1,\"tool\":\"write\",\"args\":{},\"highRisk\":true}]}", "APPROVE");
        PlanExecuteExecutor planner = new PlanExecuteExecutor(model, gateway, traces, 0, new CriticAgent(model), store);
        var result = planner.execute("请执行已选操作", user, Map.of("verificationSteps", List.of("verify")));
        PendingAction pending = result.steps().stream().filter(step -> "confirmation".equals(step.type()))
                .map(step -> store.find(String.valueOf(((Map<?, ?>) step.observation()).get("pendingId"))).orElseThrow())
                .findFirst().orElseThrow();
        assertEquals(2, pending.toolContracts().get("write").contractVersion());
        assertEquals(Map.of("type", "object"), pending.toolContracts().get("write").resultSchema());
        assertEquals(2, pending.toolContracts().get("verify").contractVersion());
        assertEquals(2, gateway.catalog.get(0).asRetrySafe().contractVersion());
        assertEquals(gateway.catalog.get(0).resultSchema(), gateway.catalog.get(0).asRetrySafe().resultSchema());
        assertTrue(PlanCheckpointIntegrity.valid(pending));
        assertTrue(gateway.calls.isEmpty());
        String oldHash = pending.planHash();
        PlanCheckpoint original = PlanCheckpointIntegrity.toCheckpoint(pending);
        assertNotEquals(oldHash, PlanCheckpointIntegrity.hash(new PlanCheckpoint(original.planVersion(), original.originalMessage(),
                original.hints(), original.steps(), original.currentStep(), original.completedOutputs(), original.evidenceIds(),
                original.verificationSteps(), Map.of("write", new ToolContractSnapshot(3, Map.of()), "verify", new ToolContractSnapshot(2, Map.of())))));
    }

    @Test @SuppressWarnings("unchecked") void redisCodecPreservesNewSnapshotAndIntegrity() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicReference<String> persisted = new AtomicReference<>();
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(call -> { persisted.set(call.getArgument(1)); return null; }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(call -> persisted.get());
        when(values.getAndDelete(anyString())).thenAnswer(call -> persisted.getAndSet(null));
        RedisPendingActionStore shared = new RedisPendingActionStore(redis,
                tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults().build());
        PendingAction original = shared.createCheckpoint(checkpoint(false), user, "trace", Duration.ofMinutes(5));
        PendingAction restored = shared.find(original.pendingId()).orElseThrow();
        assertEquals(original.toolContracts(), restored.toolContracts());
        assertEquals(original.planHash(), restored.planHash());
        assertTrue(PlanCheckpointIntegrity.valid(restored));
        assertTrue(PlanCheckpointIntegrity.valid(shared.consume(original.pendingId()).orElseThrow()));
        assertTrue(shared.find(original.pendingId()).isEmpty());

        // This fixed fixture specifies the old field shape, not recoverable bytes emitted by Hutool's record encoder.
        try (var input = getClass().getResourceAsStream("/compatibility/pending-action-v1-shape.json")) {
            assertNotNull(input);
            persisted.set(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        PendingAction legacy = shared.find("legacy-pending").orElseThrow();
        assertEquals("42", legacy.userId());
        assertEquals("legacy-trace", legacy.traceId());
        assertEquals(java.time.Instant.parse("2099-01-01T00:00:00Z"), legacy.expiresAt());
        assertEquals(9007199254740993L, legacy.args().get("amount"));
        assertNull(legacy.toolContracts());
        assertTrue(PlanCheckpointIntegrity.valid(legacy));
        gateway.catalog = List.of(tool("write", 1, Map.of()), tool("verify", 1, Map.of()));
        AgentController recovering = new AgentController(null, executor, null, traces, shared, gateway, null, null);
        login();
        assertEquals(true, recovering.confirm(legacy.pendingId()).get("ok"));
        assertEquals("legacy-pending:plan:1", gateway.calls.get(0).idempotencyKey());
        assertEquals(1, gateway.calls.get(0).expectedVersion());
        persisted.set("{}");
        assertTrue(shared.find("missing-fields").isEmpty());
    }

    private PendingAction create(boolean legacy) { return store.createCheckpoint(checkpoint(legacy), user, "trace", Duration.ofMinutes(5)); }
    private PlanCheckpoint checkpoint(boolean legacy) {
        return new PlanCheckpoint(1, "操作", Map.of(), List.of(new PlanCheckpointStep(1, "write", Map.of(), true)),
                0, Map.of(), List.of(), List.of("verify"), legacy ? null : Map.of(
                        "write", ToolContractSnapshot.of(gateway.catalog.get(0)), "verify", ToolContractSnapshot.of(gateway.catalog.get(1))));
    }
    private void rejectWithoutConsuming(PendingAction pending) {
        login();
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.confirm(pending.pendingId()));
        assertEquals(409, error.getStatusCode().value());
        assertTrue(store.find(pending.pendingId()).isPresent());
    }
    private void login() { AgentRequestContextHolder.set(new AgentRequestContext(new ServiceUser(42L, "user", null, "user", Set.of("*"), true), "token", "request")); }
    private static ToolMetadata tool(String name, int version, Map<String, Object> schema) {
        return ToolMetadata.fromDefinition(new ToolDefinition(name, name, List.of(), 60, !"write".equals(name), List.of(),
                ToolAudience.CONSUMER, ToolSideEffect.NONE, ToolRiskLevel.LOW, ToolApprovalPolicy.NONE, "core", version, schema));
    }
    private static final class Catalog implements ToolGatewayClient {
        List<ToolMetadata> catalog = List.of(tool("write", 2, Map.of("type", "object")), tool("verify", 2, Map.of()));
        final List<ToolCallContext> calls = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        boolean unavailable;
        @Override public List<ToolMetadata> tools() { return catalog; }
        @Override public List<ToolMetadata> refreshTools() {
            if (unavailable) throw new ToolExecutionException("tool_catalog_unavailable", "catalog unavailable", false);
            return catalog;
        }
        @Override public ToolResult invoke(String name, Map<String, Object> args, ToolCallContext context) {
            calls.add(context); names.add(name); return ToolResult.success(Map.of("verified", true));
        }
    }
}
