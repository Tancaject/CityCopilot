package com.citycopilot.agent.confirmation;

import com.citycopilot.agent.gateway.ToolCallContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static com.citycopilot.agent.confirmation.PlanCheckpointIntegrity.hash;

/**
 * 基于 Redis 的共享待确认动作存储。
 *
 * <p>利用 Redis TTL 自动失效，并以 {@code GETDEL} 一次性消费 pendingId，支持 Agent 多实例安全恢复计划。</p>
 */
public final class RedisPendingActionStore implements PendingActionStore {
    /** 待确认动作在 Redis 中的键前缀。 */
    private static final String KEY_PREFIX = "agent:pending:";
    /** 执行带 TTL 写入和原子读取删除的 Redis 客户端。 */
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPendingActionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 序列化计划检查点并按确认有效期写入 Redis。 */
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
                checkpoint.verificationSteps(),
                checkpoint.toolContracts()
        );
        redisTemplate.opsForValue().set(KEY_PREFIX + pendingId, objectMapper.writeValueAsString(action), ttl);
        return action;
    }

    /** 只读查询待确认动作，不延长其 TTL。 */
    @Override
    public Optional<PendingAction> find(String pendingId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + pendingId);
        return decode(json);
    }

    /** 使用 Redis {@code GETDEL} 保证跨实例仅有一个确认请求取得动作。 */
    @Override
    public Optional<PendingAction> consume(String pendingId) {
        String json = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + pendingId);
        return decode(json);
    }

    /** 反序列化动作并再次校验业务过期时间，防止 Redis TTL 边界误差。 */
    private Optional<PendingAction> decode(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        PendingAction action = objectMapper.readValue(json, PendingAction.class);
        if (action.expiresAt() == null || !action.expiresAt().isAfter(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(action);
    }
}
