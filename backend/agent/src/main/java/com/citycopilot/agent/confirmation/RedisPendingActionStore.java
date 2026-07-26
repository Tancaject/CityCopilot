package com.citycopilot.agent.confirmation;

import cn.hutool.json.JSONUtil;
import com.citycopilot.agent.gateway.ToolCallContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.citycopilot.agent.confirmation.PlanCheckpointIntegrity.hash;

public final class RedisPendingActionStore implements PendingActionStore {
    private static final String KEY_PREFIX = "agent:pending:";
    private final StringRedisTemplate redisTemplate;

    public RedisPendingActionStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

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
                "确认执行 " + current.toolName() + "，参数：" + current.args(),
                Instant.now().plus(ttl),
                checkpoint.planVersion(),
                hash(checkpoint),
                checkpoint.originalMessage(),
                checkpoint.hints(),
                checkpoint.steps(),
                checkpoint.currentStep(),
                checkpoint.completedOutputs(),
                checkpoint.evidenceIds(),
                checkpoint.verificationSteps()
        );
        redisTemplate.opsForValue().set(KEY_PREFIX + pendingId, JSONUtil.toJsonStr(action), ttl);
        return action;
    }

    @Override
    public Optional<PendingAction> find(String pendingId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + pendingId);
        return decode(json);
    }

    @Override
    public Optional<PendingAction> consume(String pendingId) {
        String json = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + pendingId);
        return decode(json);
    }

    private Optional<PendingAction> decode(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        PendingAction action = JSONUtil.toBean(json, PendingAction.class);
        if (action.expiresAt() == null || !action.expiresAt().isAfter(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(action);
    }
}
