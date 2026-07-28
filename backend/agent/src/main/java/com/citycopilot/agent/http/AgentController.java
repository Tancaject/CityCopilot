package com.citycopilot.agent.http;

import com.citycopilot.agent.confirmation.PendingAction;
import com.citycopilot.agent.confirmation.PendingActionStore;
import com.citycopilot.agent.confirmation.PlanCheckpointIntegrity;
import com.citycopilot.agent.executor.AgentExecutionResult;
import com.citycopilot.agent.executor.PlanExecuteExecutor;
import com.citycopilot.agent.executor.ReActExecutor;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGatewayClient;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.memory.ConversationMemory;
import com.citycopilot.agent.memory.ConversationScope;
import com.citycopilot.agent.memory.LongTermPreferenceMemory;
import com.citycopilot.agent.memory.PreferenceMemoryWriteException;
import com.citycopilot.agent.router.AgentRouter;
import com.citycopilot.agent.router.Mode;
import com.citycopilot.agent.security.AgentRequestContext;
import com.citycopilot.agent.security.AgentRequestContextHolder;
import com.citycopilot.agent.security.AgentSecurity;
import com.citycopilot.agent.trace.AgentTrace;
import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.trace.TraceStore;
import com.citycopilot.contract.security.ServiceUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentPingHandler pingHandler = new AgentPingHandler();
    private final ReActExecutor reActExecutor;
    private final PlanExecuteExecutor planExecuteExecutor;
    private final AgentRouter router;
    private final TraceStore traceStore;
    private final PendingActionStore pendingActionStore;
    private final ToolGatewayClient toolGateway;
    private final ConversationMemory conversationMemory;
    private final LongTermPreferenceMemory preferenceMemory;

    public AgentController(
            ReActExecutor reActExecutor,
            PlanExecuteExecutor planExecuteExecutor,
            AgentRouter router,
            TraceStore traceStore,
            PendingActionStore pendingActionStore,
            ToolGatewayClient toolGateway,
            ConversationMemory conversationMemory,
            LongTermPreferenceMemory preferenceMemory
    ) {
        this.reActExecutor = reActExecutor;
        this.planExecuteExecutor = planExecuteExecutor;
        this.router = router;
        this.traceStore = traceStore;
        this.pendingActionStore = pendingActionStore;
        this.toolGateway = toolGateway;
        this.conversationMemory = conversationMemory;
        this.preferenceMemory = preferenceMemory;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return pingHandler.ping();
    }

    @GetMapping("/ready")
    public Map<String, Object> ready() {
        int toolCount = toolGateway.tools().size();
        if (toolCount == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CORE_TOOL_CATALOG_UNAVAILABLE");
        }
        return Map.of("status", "ok", "core", "up", "toolCount", toolCount);
    }

    @GetMapping("/traces")
    public List<AgentTrace> traces(@RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        AgentSecurity.requireAuditRead(AgentRequestContextHolder.get().user());
        return traceStore.listRecent(limit == null ? 50 : limit);
    }

    @GetMapping("/traces/{traceId}")
    public AgentTrace trace(@PathVariable String traceId) {
        AgentSecurity.requireAuditRead(AgentRequestContextHolder.get().user());
        return traceStore.find(traceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown traceId: " + traceId));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        AgentRequestContext requestContext = AgentRequestContextHolder.get();
        CompletableFuture.runAsync(() -> streamChat(request, emitter, requestContext));
        return emitter;
    }

    @PostMapping("/confirm/{pendingId}")
    public Map<String, Object> confirm(@PathVariable String pendingId) {
        AgentRequestContext requestContext = AgentRequestContextHolder.get();
        ServiceUser user = requireUser(requestContext.user());
        PendingAction existing = pendingActionStore.find(pendingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Confirmation expired or consumed"));
        requireOwner(existing, user);
        if (!PlanCheckpointIntegrity.valid(existing)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAN_CHECKPOINT_HASH_MISMATCH");
        }
        PendingAction action = pendingActionStore.consume(pendingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Confirmation already consumed"));
        requireOwner(action, user);
        ToolCallContext context = AgentSecurity.toolContextFrom(
                user,
                requestContext.authorization(),
                requestContext.requestId()
        ).withIdempotencyKey(pendingId);
        AgentExecutionResult resumed = planExecuteExecutor.resume(action, context);
        boolean ok = "已批准的计划执行并复核完成。".equals(resumed.finalAnswer());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pendingId", pendingId);
        response.put("traceId", action.traceId());
        response.put("toolName", action.toolName());
        response.put("ok", ok);
        response.put("data", resumed);
        response.put("error", ok ? null : resumed.finalAnswer());
        return response;
    }

    @PostMapping("/confirm/{pendingId}/cancel")
    public Map<String, Object> cancelConfirmation(@PathVariable String pendingId) {
        ServiceUser user = requireUser(AgentRequestContextHolder.get().user());
        PendingAction existing = pendingActionStore.find(pendingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Confirmation expired or consumed"));
        requireOwner(existing, user);
        PendingAction action = pendingActionStore.consume(pendingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Confirmation already consumed"));
        return Map.of("pendingId", action.pendingId(), "traceId", action.traceId(), "cancelled", true);
    }

    private ServiceUser requireUser(ServiceUser user) {
        if (user == null || !user.authenticated() || user.id() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        }
        return user;
    }

    private void requireOwner(PendingAction action, ServiceUser user) {
        if (!String.valueOf(user.id()).equals(action.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONFIRMATION_OWNER_MISMATCH");
        }
    }

    private void appendConfirmationTrace(PendingAction action, ToolResult result) {
        traceStore.find(action.traceId()).ifPresent(trace -> {
            List<AgentTraceStep> steps = new ArrayList<>(trace.steps());
            int index = steps.size() + 1;
            steps.add(AgentTraceStep.toolCall(index, action.toolName(), action.args()));
            steps.add(AgentTraceStep.toolResult(index, action.toolName(), result));
            String answer = result.ok() ? "Confirmed action completed." : "Confirmed action failed.";
            steps.add(AgentTraceStep.finalAnswer(index + 1, answer));
            traceStore.save(new AgentTrace(
                    trace.traceId(), trace.mode(), List.copyOf(steps), answer, trace.durationMs(), trace.createdAt()
            ));
        });
    }

    private void streamChat(AgentChatRequest request, SseEmitter emitter, AgentRequestContext requestContext) {
        try {
            AgentRequestContextHolder.set(requestContext);
            emit(emitter, new AgentSseEvent("status", Map.of("phase", "agent_started")));
            String message = request == null ? null : request.getMessage();
            String sessionId = ConversationScope.sessionKey(
                    requestContext.user(),
                    request == null ? null : request.getConversationId()
            );
            String contextualMessage = conversationMemory.contextualize(sessionId, message);
            ToolCallContext context = AgentSecurity.toolContextFrom(
                    requestContext.user(), requestContext.authorization(), requestContext.requestId()
            );
            Map<String, Object> hints = hintsFrom(request);
            LongTermPreferenceMemory.Mutation memoryMutation = applyLongTermPreferences(
                    request,
                    requestContext.user(),
                    message,
                    hints
            );
            if (memoryMutation.changed() && memoryMutation.memoryOnly()) {
                AgentExecutionResult result = memoryResult(memoryMutation.responseMessage());
                for (AgentSseEvent event : AgentSseEventMapper.toEvents(result)) {
                    emit(emitter, event);
                }
                conversationMemory.appendTurn(sessionId, message, result.finalAnswer());
                emitter.complete();
                return;
            }
            Mode mode = router.classify(message);
            AgentExecutionResult result;
            if (mode == Mode.PLAN_EXECUTE) {
                result = planExecuteExecutor.executeStreaming(
                        contextualMessage,
                        context,
                        hints,
                        step -> emitStep(emitter, step),
                        delta -> emitUnchecked(emitter, new AgentSseEvent("delta", Map.of("content", delta)))
                );
                emit(emitter, new AgentSseEvent("traceId", Map.of("traceId", result.traceId(), "mode", result.mode())));
                emit(emitter, new AgentSseEvent("final", Map.of(
                        "message", result.finalAnswer(), "traceId", result.traceId(), "durationMs", result.durationMs()
                )));
            } else {
                result = reActExecutor.execute(contextualMessage, context, hints);
                for (AgentSseEvent event : AgentSseEventMapper.toEvents(result)) {
                    emit(emitter, event);
                }
            }
            conversationMemory.appendTurn(sessionId, message, result.finalAnswer());
            emitter.complete();
        } catch (PreferenceMemoryWriteException error) {
            preferenceMemoryFallback(emitter);
        } catch (Exception error) {
            fallback(emitter, error);
        } finally {
            AgentRequestContextHolder.clear();
        }
    }

    private Map<String, Object> hintsFrom(AgentChatRequest request) {
        Map<String, Object> hints = new LinkedHashMap<>();
        if (request == null) {
            return hints;
        }
        if (request.getCurrentShopId() != null) hints.put("currentShopId", request.getCurrentShopId());
        if (request.getX() != null) hints.put("x", request.getX());
        if (request.getY() != null) hints.put("y", request.getY());
        return hints;
    }

    private LongTermPreferenceMemory.Mutation applyLongTermPreferences(
            AgentChatRequest request,
            ServiceUser user,
            String message,
            Map<String, Object> hints
    ) {
        if (!isConsumerScene(request) || user == null || !user.authenticated() || user.id() == null) {
            return LongTermPreferenceMemory.Mutation.none();
        }
        LongTermPreferenceMemory.Mutation mutation = preferenceMemory.applyExplicitMessage(user.id(), message);
        Map<String, Object> preferences = preferenceMemory.promptContext(user.id());
        if (!preferences.isEmpty()) {
            hints.put("userPreferences", preferences);
        }
        if (mutation.changed()) {
            hints.put("preferenceMemoryUpdate", Map.of(
                    "savedCount", mutation.savedCount(),
                    "deletedCount", mutation.deletedCount()
            ));
        }
        return mutation;
    }

    private boolean isConsumerScene(AgentChatRequest request) {
        return request == null || request.getScene() == null || !"admin".equalsIgnoreCase(request.getScene());
    }

    private AgentExecutionResult memoryResult(String answer) {
        String traceId = UUID.randomUUID().toString();
        AgentTraceStep finalStep = AgentTraceStep.finalAnswer(1, answer);
        AgentExecutionResult result = new AgentExecutionResult(
                traceId,
                "MEMORY",
                List.of(finalStep),
                answer,
                0
        );
        traceStore.save(new AgentTrace(traceId, "MEMORY", result.steps(), answer, 0, Instant.now()));
        return result;
    }

    private void preferenceMemoryFallback(SseEmitter emitter) {
        try {
            emit(emitter, new AgentSseEvent("error", Map.of(
                    "code", "preference_memory_unavailable",
                    "message", "长期偏好暂时无法保存，请稍后重试。"
            )));
            emit(emitter, new AgentSseEvent("final", Map.of(
                    "message", "这次长期偏好没有保存成功，请稍后重试。",
                    "fallback", false
            )));
            emitter.complete();
        } catch (Exception fallbackError) {
            emitter.completeWithError(fallbackError);
        }
    }

    private void fallback(SseEmitter emitter, Exception error) {
        try {
            emit(emitter, new AgentSseEvent("error", Map.of(
                    "code", "agent_provider_error",
                    "message", error.getMessage() == null ? "Agent provider unavailable" : error.getMessage()
            )));
            emit(emitter, new AgentSseEvent("final", Map.of(
                    "message", "Agent is temporarily unavailable. Please retry later.", "fallback", false
            )));
            emitter.complete();
        } catch (Exception fallbackError) {
            emitter.completeWithError(fallbackError);
        }
    }

    private void emit(SseEmitter emitter, AgentSseEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
    }

    private void emitStep(SseEmitter emitter, AgentTraceStep step) {
        AgentSseEvent event = AgentSseEventMapper.fromStep(step);
        if (event != null) emitUnchecked(emitter, event);
    }

    private void emitUnchecked(SseEmitter emitter, AgentSseEvent event) {
        try {
            emit(emitter, event);
        } catch (IOException error) {
            throw new IllegalStateException("SSE client disconnected", error);
        }
    }
}
