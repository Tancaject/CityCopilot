package com.citycopilot.agent.executor;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.citycopilot.agent.budget.AgentBudget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高风险写工具的模型安全复核器。
 *
 * <p>它不执行工具，只根据原始意图、前置输出、参数与权限做第二道许可判断；模型异常或输出不符合协议时一律拒绝。</p>
 */
public final class CriticAgent {
    /** 限制批评器只输出 APPROVE 或带简短原因的 REJECT。 */
    private static final String CRITIC_PROMPT = """
            You are CityCopilot's safety critic for high-risk local-life tool calls.
            Review whether the next write operation is safe and clearly authorized by the user intent.
            Reply with exactly one line:
            APPROVE
            or
            REJECT: <short reason>
            Reject if the user intent is ambiguous, required ids are placeholders, or the action changes orders, payments, refunds, publishing, or batch jobs without clear intent.
            """;

    /** 执行无工具文本复核的模型客户端。 */
    private final AgentLlmClient llmClient;
    /** 将复核上下文编码为稳定 JSON，避免自然语言拼接歧义。 */
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public CriticAgent(AgentLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 复核下一项高风险调用是否得到清晰授权。
     *
     * @param userIntent 用户原始意图
     * @param priorSteps 已执行步骤摘要
     * @param toolName 下一工具名
     * @param args 下一工具参数
     * @param requiredPermissions 工具声明的权限
     * @return 许可决定；模型不可用、异常或响应不可识别时均为拒绝
     */
    public CriticDecision review(
            String userIntent,
            List<Map<String, Object>> priorSteps,
            String toolName,
            Map<String, Object> args,
            List<String> requiredPermissions
    ) {
        try {
            String response = llmClient.complete(CRITIC_PROMPT, List.of(request(userIntent, priorSteps, toolName, args, requiredPermissions)));
            if (response == null || response.isBlank()) {
                return CriticDecision.reject("critic unavailable");
            }
            String normalized = response.trim();
            if (normalized.equalsIgnoreCase("APPROVE")) {
                return CriticDecision.allow();
            }
            if (normalized.toUpperCase().startsWith("REJECT:")) {
                return CriticDecision.reject(normalized.substring("REJECT:".length()).trim());
            }
            return CriticDecision.reject("unrecognized critic response");
        } catch (Exception e) {
            AgentBudget.rethrowIfStopped(e);
            return CriticDecision.reject(e.getMessage() == null ? "critic failed" : e.getMessage());
        }
    }

    /** 将复核输入序列化为单个模型用户消息。 */
    private String request(
            String userIntent,
            List<Map<String, Object>> priorSteps,
            String toolName,
            Map<String, Object> args,
            List<String> requiredPermissions
    ) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userIntent", userIntent == null ? "" : userIntent);
        request.put("priorSteps", priorSteps == null ? List.of() : priorSteps);
        request.put("nextTool", toolName);
        request.put("nextArgs", args == null ? Map.of() : args);
        request.put("requiredPermissions", requiredPermissions == null ? List.of() : requiredPermissions);
        return mapper.writeValueAsString(request);
    }

    /**
     * 批评器的最小结果。
     *
     * @param approved 是否允许继续进入用户确认或工具执行
     * @param reason 拒绝原因；通过时为空
     */
    public record CriticDecision(boolean approved, String reason) {
        public static CriticDecision allow() {
            return new CriticDecision(true, null);
        }

        public static CriticDecision reject(String reason) {
            return new CriticDecision(false, reason == null || reason.isBlank() ? "rejected" : reason);
        }
    }
}
