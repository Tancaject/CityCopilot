package com.citycopilot.agent.executor;

import com.citycopilot.agent.budget.AgentBudget;

import com.citycopilot.agent.gateway.ToolCallContext;
import com.citycopilot.agent.gateway.ToolGatewayClient;
import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.trace.AgentTrace;
import com.citycopilot.agent.trace.AgentTraceStep;
import com.citycopilot.agent.trace.TraceStore;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.agent.tool.ToolPolicyEngine;
import com.citycopilot.contract.tool.ToolAudience;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/**
 * 面向普通用户查询的 ReAct 循环执行器。
 *
 * <p>每轮让模型在“最终回答”和“调用一个消费者工具”之间决策，经受众白名单校验后通过 ToolGateway 调用 Core，并把全过程写入 Trace。</p>
 */
public final class ReActExecutor {
    /** 统一执行远程 Core 工具的网关。 */
    private final ToolGatewayClient gateway;
    /** 生成下一步结构化决策的模型客户端。 */
    private final AgentLlmClient llmClient;
    /** 保存本次循环的完整执行轨迹。 */
    private final TraceStore traceStore;
    /** 单次请求允许的最大决策轮数，防止模型无限循环。 */
    private final int maxSteps;
    /** 根据工具受众过滤普通用户不可见的管理工具。 */
    private final ToolPolicyEngine toolPolicyEngine = new ToolPolicyEngine();

    public ReActExecutor(ToolGatewayClient gateway, AgentLlmClient llmClient, TraceStore traceStore, int maxSteps) {
        this.gateway = gateway;
        this.llmClient = llmClient;
        this.traceStore = traceStore;
        this.maxSteps = Math.max(1, maxSteps);
    }

    /** 使用空提示执行一轮 ReAct 请求。 */
    public AgentExecutionResult execute(String message, ToolCallContext context) {
        return execute(message, context, Map.of());
    }

    /**
     * 执行受步数、受众和重复调用保护的 ReAct 循环。
     *
     * @param message 含会话上下文的用户请求
     * @param context 用户、权限与审计上下文
     * @param hints 位置、当前店铺和长期偏好等编排提示
     * @return 包含最终答案与完整步骤的执行结果
     */
    public AgentExecutionResult execute(String message, ToolCallContext context, Map<String, Object> hints) {
        if (AgentBudget.current() != null) AgentBudget.current().context(Map.of("message", message == null ? "" : message, "hints", hints == null ? Map.of() : hints));
        String traceId = UUID.randomUUID().toString();
        Instant start = Instant.now();
        List<AgentTraceStep> steps = new ArrayList<>();
        Map<String, Integer> duplicateGuard = new HashMap<>();
        String finalAnswer = null;

        for (int index = 0; index < maxSteps; index++) {
            AgentBudget.checkCurrent();
            // 每轮重新以消费者受众过滤目录，模型看不到后台管理工具。
            List<ToolMetadata> availableTools = toolPolicyEngine.visibleTools(gateway.tools(), ToolAudience.CONSUMER);
            AgentDecision decision = llmClient.next(message, availableTools, List.copyOf(steps), hints == null ? Map.of() : hints);
            if (decision.type() == AgentDecision.Type.FINAL) {
                finalAnswer = decision.finalAnswer();
                steps.add(AgentTraceStep.finalAnswer(index + 1, finalAnswer));
                break;
            }

            Map<String, Object> resolvedArgs = RecommendationPreferenceResolver.applyToolDefaults(
                    decision.toolName(),
                    message,
                    hints,
                    decision.args()
            );
            String callKey = decision.toolName() + ":" + resolvedArgs;
            int repeated = duplicateGuard.merge(callKey, 1, Integer::sum);
            AgentTraceStep callStep = AgentTraceStep.toolCall(index + 1, decision.toolName(), resolvedArgs);
            steps.add(callStep);
            if (repeated > 2) {
                finalAnswer = "Agent stopped because the same tool call repeated.";
                steps.add(AgentTraceStep.error(index + 1, "duplicate_tool_call", finalAnswer));
                break;
            }
            ToolMetadata selected = availableTools.stream().filter(tool -> tool.name().equals(decision.toolName()))
                    .findFirst().orElse(null);
            if (selected == null) {
                finalAnswer = "当前 Agent 无权调用该工具。";
                steps.add(AgentTraceStep.error(index + 1, "tool_not_allowed", finalAnswer));
                break;
            }

            // 每个步骤派生稳定且唯一的幂等键，重试不会重复产生业务写入。
            ToolCallContext invocationContext = (context == null ? new ToolCallContext(null, Set.of()) : context)
                    .withIdempotencyKey(traceId + ":react:" + (index + 1))
                    .withExpectedVersion(selected.contractVersion());
            ToolResult result = gateway.invoke(decision.toolName(), resolvedArgs, invocationContext);
            steps.add(AgentTraceStep.toolResult(index + 1, decision.toolName(), result));
            String bookingPause = BookingToolOutcome.stopMessage(result);
            if (bookingPause != null) {
                finalAnswer = bookingPause;
                steps.add(AgentTraceStep.finalAnswer(index + 1, finalAnswer));
                break;
            }
            if (!result.ok() && !result.error().retryable()) {
                finalAnswer = "工具调用失败：" + result.error().message();
                steps.add(AgentTraceStep.finalAnswer(index + 1, finalAnswer));
                break;
            }
        }

        if (finalAnswer == null) {
            finalAnswer = "Agent reached the maximum step limit.";
            steps.add(AgentTraceStep.error(maxSteps, "max_steps", finalAnswer));
        }
        long durationMs = Duration.between(start, Instant.now()).toMillis();
        AgentExecutionResult result = new AgentExecutionResult(traceId, "REACT", List.copyOf(steps), finalAnswer, durationMs);
        // 统一落库完整轨迹，供 SSE 展示、审计和后台排障读取。
        traceStore.save(new AgentTrace(traceId, "REACT", result.steps(), finalAnswer, durationMs, Instant.now()));
        return result;
    }
}
