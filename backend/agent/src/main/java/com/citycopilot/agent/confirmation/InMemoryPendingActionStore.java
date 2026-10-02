package com.citycopilot.agent.confirmation;

import com.citycopilot.agent.gateway.ToolCallContext;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static com.citycopilot.agent.confirmation.PlanCheckpointIntegrity.hash;

/**
 * 单实例待确认动作存储。
 *
 * <p>用于本地开发或无 Redis 的测试环境；数据不跨进程共享，生产多实例应使用 {@link RedisPendingActionStore}。</p>
 */
public final class InMemoryPendingActionStore implements PendingActionStore {
    /** 按 pendingId 保存尚未消费的动作。 */
    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();

    /** 创建带完整恢复信息与完整性哈希的内存检查点。 */
    @Override
    public PendingAction createCheckpoint(
            PlanCheckpoint checkpoint,
            ToolCallContext context,
            String traceId,
            Duration ttl
    ) {
        String pendingId = UUID.randomUUID().toString();
        PlanCheckpointStep current = checkpoint.steps().get(checkpoint.currentStep());
        PendingAction action = new PendingAction(
                pendingId,
                current.toolName(),
                current.args(),
                context == null ? "anonymous" : context.userId(),
                traceId,
                summary(current.toolName(), current.args()),
                Instant.now().plus(ttl),
                checkpoint.planVersion(),
                hash(checkpoint),
                checkpoint.originalMessage(),
                checkpoint.hints(),
                checkpoint.steps(),
                checkpoint.currentStep(),
                checkpoint.completedOutputs(),
                checkpoint.evidenceIds(),
                checkpoint.verificationSteps(),
                checkpoint.toolContracts()
        );
        actions.put(pendingId, action);
        return action;
    }

    /** 查询有效动作；查询时顺带清除已过期条目。 */
    @Override
    public Optional<PendingAction> find(String pendingId) {
        PendingAction action = actions.get(pendingId);
        if (expired(action)) {
            actions.remove(pendingId, action);
            return Optional.empty();
        }
        return Optional.ofNullable(action);
    }

    /** 先删除后返回，保证同一 JVM 内一次性消费。 */
    @Override
    public Optional<PendingAction> consume(String pendingId) {
        PendingAction action = actions.remove(pendingId);
        return expired(action) ? Optional.empty() : Optional.ofNullable(action);
    }

    private boolean expired(PendingAction action) {
        return action != null && !action.expiresAt().isAfter(Instant.now());
    }

    private String summary(String toolName, Map<String, Object> args) {
        return "确认执行 " + toolName + "，参数：" + (args == null ? Map.of() : args);
    }
}
