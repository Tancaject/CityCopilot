package com.citycopilot.agent.executor;

import com.citycopilot.agent.trace.AgentTraceStep;

import java.util.List;

/**
 * 一次 Agent 编排执行的完整返回值。
 *
 * @param traceId 串联模型、工具与审计记录的轨迹标识
 * @param mode 实际采用的执行模式
 * @param steps 按发生顺序记录的轨迹步骤
 * @param finalAnswer 返回用户的最终文本
 * @param durationMs 端到端耗时毫秒数
 */
public record AgentExecutionResult(
        String traceId,
        String mode,
        List<AgentTraceStep> steps,
        String finalAnswer,
        long durationMs
) {
}
