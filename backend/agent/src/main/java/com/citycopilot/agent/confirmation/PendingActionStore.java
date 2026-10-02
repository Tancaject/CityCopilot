package com.citycopilot.agent.confirmation;

import com.citycopilot.agent.gateway.ToolCallContext;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 高风险动作确认存储契约。
 *
 * <p>执行器先写入带 TTL 的计划检查点，确认接口再按用户与 Trace 校验并一次性消费；实现必须避免同一确认被重复执行。</p>
 */
public interface PendingActionStore {
    /**
     * 为单个工具调用创建兼容检查点。
     *
     * @param toolName 待执行工具名
     * @param args 工具参数
     * @param context 调用用户上下文
     * @param traceId 所属轨迹
     * @param ttl 确认有效期
     * @return 已持久化的待确认动作
     */
    default PendingAction create(
            String toolName,
            Map<String, Object> args,
            ToolCallContext context,
            String traceId,
            Duration ttl
    ) {
        return createCheckpoint(
                new PlanCheckpoint(
                        1,
                        "",
                        Map.of(),
                        java.util.List.of(new PlanCheckpointStep(1, toolName, args, true)),
                        0,
                        Map.of(),
                        java.util.List.of(),
                        java.util.List.of()
                ),
                context,
                traceId,
                ttl
        );
    }

    /**
     * 保存完整计划的暂停点。
     *
     * @param checkpoint 不可变计划快照
     * @param context 创建者上下文
     * @param traceId 所属轨迹
     * @param ttl 确认有效期
     * @return 带随机 pendingId 的待确认动作
     */
    PendingAction createCheckpoint(
            PlanCheckpoint checkpoint,
            ToolCallContext context,
            String traceId,
            Duration ttl
    );

    /** @return 尚未过期的动作；不存在或已过期时为空 */
    Optional<PendingAction> find(String pendingId);

    /**
     * 原子取得并删除待确认动作。
     *
     * @return 首次有效消费的动作；重复、过期或不存在时为空
     */
    Optional<PendingAction> consume(String pendingId);
}
