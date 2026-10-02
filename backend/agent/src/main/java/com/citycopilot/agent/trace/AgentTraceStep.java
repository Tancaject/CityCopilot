package com.citycopilot.agent.trace;

import com.citycopilot.agent.gateway.ToolResult;
import com.citycopilot.agent.confirmation.PendingAction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Trace 中的单个协议步骤。
 *
 * @param index 步骤序号
 * @param type tool_call、tool_result、confirmation、error 或 final
 * @param toolName 关联工具名
 * @param args 工具调用参数快照
 * @param observation 工具结果或确认详情
 * @param errorCode 稳定错误码
 * @param message 面向用户或排障的文字
 */
public record AgentTraceStep(
        int index,
        String type,
        String toolName,
        Map<String, Object> args,
        Object observation,
        String errorCode,
    String message
) {
    /** @return 参数已防御性复制的工具调用步骤 */
    public static AgentTraceStep toolCall(int index, String toolName, Map<String, Object> args) {
        return new AgentTraceStep(index, "tool_call", toolName, copyArgs(args), null, null, null);
    }

    /** @return 携带标准 ToolResult 的观察步骤 */
    public static AgentTraceStep toolResult(int index, String toolName, ToolResult result) {
        return new AgentTraceStep(index, "tool_result", toolName, Map.of(), result, null, null);
    }

    /** @return 结束本段执行的最终答案步骤 */
    public static AgentTraceStep finalAnswer(int index, String answer) {
        return new AgentTraceStep(index, "final", null, Map.of(), null, null, answer);
    }

    /**
     * @return 只暴露 pendingId、摘要和过期时间的确认步骤，不泄露完整恢复快照
     */
    public static AgentTraceStep confirmation(int index, PendingAction action) {
        return new AgentTraceStep(
                index,
                "confirmation",
                action.toolName(),
                copyArgs(action.args()),
                Map.of(
                        "pendingId", action.pendingId(),
                        "summary", action.summary(),
                        "expiresAt", action.expiresAt().toString()
                ),
                null,
                "等待用户确认"
        );
    }

    /** @return 携带稳定错误码的失败步骤 */
    public static AgentTraceStep error(int index, String errorCode, String message) {
        return new AgentTraceStep(index, "error", null, Map.of(), null, errorCode, message);
    }

    private static Map<String, Object> copyArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
