package com.citycopilot.agent.http;

import com.citycopilot.agent.executor.AgentExecutionResult;
import com.citycopilot.agent.executor.PlanExecuteExecutor;
import com.citycopilot.agent.executor.ReActExecutor;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.router.AgentRouter;
import com.citycopilot.agent.router.Mode;
import com.citycopilot.agent.security.AgentSecurity;
import com.citycopilot.agent.trace.AgentTrace;
import com.citycopilot.agent.trace.TraceStore;
import com.citycopilot.core.dto.AiChatRequest;
import com.citycopilot.core.dto.AiChatResponse;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.AiAssistantService;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentPingHandler pingHandler = new AgentPingHandler();
    private final ReActExecutor reActExecutor;
    private final PlanExecuteExecutor planExecuteExecutor;
    private final AgentRouter router;
    private final TraceStore traceStore;
    private final PermissionPolicy permissionPolicy;
    private final AiAssistantService fallbackAiAssistantService;

    public AgentController(
            ReActExecutor reActExecutor,
            PlanExecuteExecutor planExecuteExecutor,
            AgentRouter router,
            TraceStore traceStore,
            PermissionPolicy permissionPolicy,
            ObjectProvider<AiAssistantService> fallbackAiAssistantService
    ) {
        this.reActExecutor = reActExecutor;
        this.planExecuteExecutor = planExecuteExecutor;
        this.router = router;
        this.traceStore = traceStore;
        this.permissionPolicy = permissionPolicy;
        this.fallbackAiAssistantService = fallbackAiAssistantService.getIfAvailable();
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return pingHandler.ping();
    }

    @GetMapping("/traces")
    @RequirePermission(PermissionCodes.AUDIT_READ)
    public List<AgentTrace> traces(@RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        AgentSecurity.requireAuditRead(UserHolder.getUser(), permissionPolicy);
        return traceStore.listRecent(limit == null ? 50 : limit);
    }

    @GetMapping("/traces/{traceId}")
    @RequirePermission(PermissionCodes.AUDIT_READ)
    public AgentTrace trace(@PathVariable String traceId) {
        AgentSecurity.requireAuditRead(UserHolder.getUser(), permissionPolicy);
        return traceStore.find(traceId).orElseThrow(() -> new IllegalArgumentException("Unknown traceId: " + traceId));
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        UserDTO user = UserHolder.getUser();
        CompletableFuture.runAsync(() -> streamChat(request, emitter, user));
        return emitter;
    }

    private void streamChat(AiChatRequest request, SseEmitter emitter, UserDTO user) {
        try {
            if (user != null) {
                UserHolder.saveUser(user);
            }
            emit(emitter, new AgentSseEvent("status", Map.of("phase", "agent_started")));
            String message = request == null ? null : request.getMessage();
            ToolCallContext context = AgentSecurity.toolContextFrom(user, permissionPolicy);
            Map<String, Object> hints = hintsFrom(request);
            AgentExecutionResult result = router.classify(message) == Mode.PLAN_EXECUTE
                    ? planExecuteExecutor.execute(message, context, hints)
                    : reActExecutor.execute(message, context, hints);
            for (AgentSseEvent event : AgentSseEventMapper.toEvents(result)) {
                emit(emitter, event);
            }
            emitter.complete();
        } catch (Exception error) {
            fallback(request, emitter, error);
        } finally {
            UserHolder.removeUser();
        }
    }

    private Map<String, Object> hintsFrom(AiChatRequest request) {
        Map<String, Object> hints = new LinkedHashMap<>();
        if (request == null) {
            return hints;
        }
        if (request.getCurrentShopId() != null) {
            hints.put("currentShopId", request.getCurrentShopId());
        }
        if (request.getX() != null) {
            hints.put("x", request.getX());
        }
        if (request.getY() != null) {
            hints.put("y", request.getY());
        }
        return hints;
    }

    private void fallback(AiChatRequest request, SseEmitter emitter, Exception error) {
        try {
            emit(emitter, new AgentSseEvent("error", Map.of(
                    "code", "agent_provider_error",
                    "message", error.getMessage() == null ? "Agent provider unavailable" : error.getMessage()
            )));
            if (fallbackAiAssistantService != null) {
                AiChatResponse response = fallbackAiAssistantService.chat(request);
                emit(emitter, new AgentSseEvent("message", response));
                emit(emitter, new AgentSseEvent("final", Map.of(
                        "message", response.getMessage(),
                        "fallback", true
                )));
            }
            emitter.complete();
        } catch (Exception fallbackError) {
            emitter.completeWithError(fallbackError);
        }
    }

    private void emit(SseEmitter emitter, AgentSseEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
    }
}
