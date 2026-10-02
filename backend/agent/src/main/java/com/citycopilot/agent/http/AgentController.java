package com.citycopilot.agent.http;

import com.citycopilot.agent.confirmation.PendingAction;
import com.citycopilot.agent.gateway.ToolExecutionException;
import com.citycopilot.agent.budget.AgentBudget;
import com.citycopilot.agent.budget.AgentBudgetException;
import com.citycopilot.agent.budget.AgentExecutionRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import com.citycopilot.agent.confirmation.PendingActionStore;
import com.citycopilot.agent.confirmation.PlanCheckpointIntegrity;
import com.citycopilot.agent.executor.AgentExecutionResult;
import com.citycopilot.agent.executor.PlanExecuteExecutor;
import com.citycopilot.agent.executor.ReActExecutor;
import com.citycopilot.agent.executor.LlmUsageTracker;
import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGatewayClient;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 面向前端的 Agent HTTP/SSE 控制器。
 *
 * <p>负责会话上下文、长期偏好、模式路由、流式事件和高风险确认；业务工具仍经 ToolGateway 交给 Core，控制器本身不直接修改业务数据。</p>
 */
@RestController
@RequestMapping("/agent")
public class AgentController {
    /** 无外部依赖的进程存活处理器。 */
    private final AgentPingHandler pingHandler = new AgentPingHandler();
    /** 普通单步推理循环。 */
    private final ReActExecutor reActExecutor;
    /** 多步骤、可确认和可恢复的计划执行器。 */
    private final PlanExecuteExecutor planExecuteExecutor;
    /** 根据当前请求选择执行模式。 */
    private final AgentRouter router;
    /** Trace 查询、SSE 展示与确认恢复所需存储。 */
    private final TraceStore traceStore;
    /** 一次性高风险确认检查点存储。 */
    private final PendingActionStore pendingActionStore;
    /** 用于就绪检查的远程工具目录入口。 */
    private final ToolGatewayClient toolGateway;
    /** 保存最近对话轮次的短期会话记忆。 */
    private final ConversationMemory conversationMemory;
    /** 提取、保存和读取用户显式长期偏好。 */
    private final LongTermPreferenceMemory preferenceMemory;
    private AgentExecutionRuntime runtime = AgentExecutionRuntime.directUse();

    @Autowired
    void configureRuntime(AgentExecutionRuntime runtime) { this.runtime = runtime; }

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

    /** @return 仅证明 Agent 进程可响应的存活信息 */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return pingHandler.ping();
    }

    /**
     * 检查 Agent 是否已取得至少一个 Core 工具。
     *
     * @return Core 可用时的工具数量
     * @throws ResponseStatusException 工具目录为空时返回 503
     */
    @GetMapping("/ready")
    public Map<String, Object> ready() {
        int toolCount = toolGateway.tools().size();
        if (toolCount == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CORE_TOOL_CATALOG_UNAVAILABLE");
        }
        return Map.of("status", "ok", "core", "up", "toolCount", toolCount);
    }

    /**
     * 返回最近 Trace，仅允许具有审计读取权限的用户访问。
     *
     * @param limit 最大返回数量
     */
    @GetMapping("/traces")
    public List<AgentTrace> traces(@RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        AgentSecurity.requireAuditRead(AgentRequestContextHolder.get().user());
        return traceStore.listRecent(limit == null ? 50 : limit);
    }

    /** 查询单条 Agent 执行轨迹，并执行同样的审计权限校验。 */
    @GetMapping("/traces/{traceId}")
    public AgentTrace trace(@PathVariable String traceId) {
        AgentSecurity.requireAuditRead(AgentRequestContextHolder.get().user());
        return traceStore.find(traceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown traceId: " + traceId));
    }

    /**
     * 启动异步 Agent 对话并立即返回 SSE 连接。
     *
     * @param request 用户消息与页面提示
     * @return 使用本轮剩余截止时间的 SSE emitter
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest request) {
        AgentBudget budget = AgentBudget.current() == null ? runtime.newBudget() : AgentBudget.current();
        try { budget.message(request == null ? null : request.getMessage()); }
        catch (RuntimeException error) { if (AgentBudget.current() == null) runtime.release(budget); throw error; }
        SseEmitter emitter = new SseEmitter(Math.max(1L, budget.remaining().toMillis()));
        AgentRequestContext requestContext = AgentRequestContextHolder.get();
        emitter.onTimeout(() -> {
            budget.cancel("agent_deadline_exceeded");
            budgetStopped(emitter, budget.failure("agent_deadline_exceeded"));
        });
        emitter.onError(error -> budget.cancel("agent_cancelled"));
        emitter.onCompletion(() -> { if (!budget.finished()) budget.cancel("agent_cancelled"); });
        runtime.submit(budget, () -> { streamChat(request, emitter, requestContext); return null; },
                error -> budgetStopped(emitter, error));
        return emitter;
    }

    /**
     * 校验并一次性消费高风险确认，然后从保存的计划位置继续执行。
     *
     * @param pendingId 确认事件中返回的随机标识
     * @return 续跑结果；过期、他人确认、哈希不匹配或重复消费分别返回明确 HTTP 错误
     */
    @PostMapping("/confirm/{pendingId}")
    public Map<String, Object> confirm(@PathVariable String pendingId) {
        AgentRequestContext requestContext = AgentRequestContextHolder.get();
        return runtime.call(() -> resumeConfirmation(pendingId, requestContext));
    }

    private Map<String, Object> resumeConfirmation(String pendingId, AgentRequestContext requestContext) {
        ServiceUser user = AgentSecurity.requireAuthenticated(requestContext.user());
        PendingAction existing = pendingActionStore.find(pendingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Confirmation expired or consumed"));
        requireOwner(existing, user);
        if (!PlanCheckpointIntegrity.valid(existing)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAN_CHECKPOINT_HASH_MISMATCH");
        }
        if (AgentBudget.current() != null) {
            AgentBudget.current().context(existing);
            if (existing.steps().size() + existing.verificationSteps().size() > AgentBudget.current().limits().getPlanSteps()) {
                AgentBudget.current().reject("agent_plan_too_large");
            }
        }
        try {
            planExecuteExecutor.requireCompatibleContracts(existing);
        } catch (ToolExecutionException changed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, changed.code() + ": " + changed.getMessage());
        }
        // 校验通过后再原子消费，确保并发确认中只有一个请求可以继续计划。
        AgentBudget.checkCurrent();
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
        if (AgentBudget.current() != null) AgentBudget.current().response(response);
        return response;
    }

    /**
     * 由创建者一次性消费并取消待确认动作，不执行任何工具。
     */
    @PostMapping("/confirm/{pendingId}/cancel")
    public Map<String, Object> cancelConfirmation(@PathVariable String pendingId) {
        ServiceUser user = AgentSecurity.requireAuthenticated(AgentRequestContextHolder.get().user());
        PendingAction existing = pendingActionStore.find(pendingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Confirmation expired or consumed"));
        requireOwner(existing, user);
        PendingAction action = pendingActionStore.consume(pendingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Confirmation already consumed"));
        return Map.of("pendingId", action.pendingId(), "traceId", action.traceId(), "cancelled", true);
    }

    /** 阻止 pendingId 泄露后被其他登录用户确认或取消。 */
    private void requireOwner(PendingAction action, ServiceUser user) {
        if (!String.valueOf(user.id()).equals(action.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONFIRMATION_OWNER_MISMATCH");
        }
    }

    /**
     * 在异步线程中完成会话补全、偏好更新、模式执行和 SSE 收尾。
     *
     * <p>无论成功、降级还是客户端断开，都会清理请求上下文，避免线程池复用造成身份串线。</p>
     */
    private void streamChat(AgentChatRequest request, SseEmitter emitter, AgentRequestContext requestContext) {
        LlmUsageTracker.begin();
        try {
            AgentRequestContextHolder.set(requestContext);
            emit(emitter, new AgentSseEvent("status", Map.of("phase", "agent_started")));
            String message = request == null ? null : request.getMessage();
            String sessionId = ConversationScope.sessionKey(
                    requestContext.user(),
                    request == null ? null : request.getConversationId()
            );
            // 在调用模型前拼接限定轮数的短期历史，使执行器本身保持无会话状态。
            String contextualMessage = conversationMemory.contextualize(sessionId, message);
            if (AgentBudget.current() != null) AgentBudget.current().context(contextualMessage);
            ToolCallContext context = AgentSecurity.toolContextFrom(
                    requestContext.user(), requestContext.authorization(), requestContext.requestId()
            );
            Map<String, Object> hints = hintsFrom(request);
            // 仅提取用户本轮明确表达的偏好；记忆作为不可信推荐提示，不能授权工具副作用。
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
                // 纯“记住/忘记”请求无需调用模型，但仍写入短期会话保持对话连贯。
                conversationMemory.appendTurn(sessionId, message, result.finalAnswer());
                completeEmission(emitter);
                return;
            }
            // 路由只依据当前用户输入，历史文本不会误触发多步骤模式。
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
            // 完成最终事件后保存本轮，下一次请求才能看到这组问答。
            conversationMemory.appendTurn(sessionId, message, result.finalAnswer());
            emit(emitter, new AgentSseEvent("usage", LlmUsageTracker.snapshot()));
            String warning = LlmUsageTracker.warning();
            if (warning != null) {
                // This is a completed SSE event, not a stream exception: tool results and confirmation cards remain visible.
                emit(emitter, new AgentSseEvent("error", Map.of("code", "MODEL_UNAVAILABLE", "message", warning)));
            }
            completeEmission(emitter);
        } catch (AgentBudgetException error) {
            budgetStopped(emitter, error);
        } catch (PreferenceMemoryWriteException error) {
            preferenceMemoryFallback(emitter);
        } catch (Exception error) {
            fallback(emitter, error);
        } finally {
            LlmUsageTracker.clear();
            AgentRequestContextHolder.clear();
        }
    }

    /** 提取可传给编排器的位置和当前店铺提示；不包含任何授权信息。 */
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

    /**
     * 对已登录消费者应用显式偏好变更，并把当前偏好投影加入模型提示。
     *
     * @return 本轮保存/删除统计；管理场景或匿名用户返回无变更
     * @throws PreferenceMemoryWriteException 写库失败时中止本轮，避免误报已记住
     */
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

    /** 为纯记忆指令构造并持久化不含工具步骤的最小执行结果。 */
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

    /** 长期偏好写入失败时发送明确错误，且不伪装为成功降级。 */
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
            completeEmission(emitter);
        } catch (Exception fallbackError) {
            emitter.completeWithError(fallbackError);
        }
    }

    /** 未分类异常的 SSE 终止路径，尽力发送 error 与 final 后关闭连接。 */
    private void fallback(SseEmitter emitter, Exception error) {
        try { AgentBudget.rethrowIfStopped(error); }
        catch (AgentBudgetException stopped) { budgetStopped(emitter, stopped); return; }
        try {
            emit(emitter, new AgentSseEvent("error", Map.of(
                    "code", "agent_provider_error",
                    "message", error.getMessage() == null ? "Agent provider unavailable" : error.getMessage()
            )));
            emit(emitter, new AgentSseEvent("final", Map.of(
                    "message", "Agent is temporarily unavailable. Please retry later.", "fallback", false
            )));
            completeEmission(emitter);
        } catch (Exception fallbackError) {
            emitter.completeWithError(fallbackError);
        }
    }

    /** 将内部事件名称和载荷写成标准 SSE 帧。 */
    private void emit(SseEmitter emitter, AgentSseEvent event) throws IOException {
        if (AgentBudget.current() != null) AgentBudget.current().response(event.data());
        try { emitter.send(SseEmitter.event().name(event.event()).data(event.data())); }
        catch (IOException error) {
            if (AgentBudget.current() != null) AgentBudget.current().cancel("agent_cancelled");
            throw error;
        }
    }

    private void completeEmission(SseEmitter emitter) {
        if (AgentBudget.current() != null) AgentBudget.current().finish();
        emitter.complete();
    }
    private void budgetStopped(SseEmitter emitter, AgentBudgetException error) {
        try {
            // Terminal control frame is fixed-size and does not spend an already exhausted data budget.
            emitter.send(SseEmitter.event().name("error").data(Map.of("code", error.code(),
                    "message", error.getMessage(), "writeOutcomeUnknown", error.writeOutcomeUnknown())));
            completeEmission(emitter);
        } catch (Exception disconnected) { emitter.completeWithError(error); }
    }

    private void emitStep(SseEmitter emitter, AgentTraceStep step) {
        AgentSseEvent event = AgentSseEventMapper.fromStep(step);
        if (event != null) emitUnchecked(emitter, event);
    }

    /** 将客户端断开转换为运行时异常，使主流程进入统一清理路径。 */
    private void emitUnchecked(SseEmitter emitter, AgentSseEvent event) {
        try {
            emit(emitter, event);
        } catch (IOException error) {
            throw new IllegalStateException("SSE client disconnected", error);
        }
    }
}
