package com.citycopilot.agent.trace;

import java.time.Instant;
import java.util.List;

/**
 * 一次 Agent 执行的持久化轨迹。
 *
 * @param traceId 跨模型、工具、SSE 和确认流程的唯一标识
 * @param mode 实际执行模式
 * @param steps 按顺序记录的决策、调用、结果、确认与错误
 * @param finalAnswer 最终用户答案或暂停/失败说明
 * @param durationMs 本段执行耗时
 * @param createdAt 最近保存时间
 */
public record AgentTrace(
        String traceId,
        String mode,
        List<AgentTraceStep> steps,
        String finalAnswer,
        long durationMs,
        Instant createdAt
) {
}
