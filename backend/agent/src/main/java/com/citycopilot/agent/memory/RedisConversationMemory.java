package com.citycopilot.agent.memory;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis List 的共享短期会话记忆。
 *
 * <p>保存最近六轮问答并设置滑动过期时间，使多实例 Agent 能继续同一会话；读写失败均按无记忆处理，不中断聊天。</p>
 */
public final class RedisConversationMemory implements ConversationMemory {
    /** 会话 List 的 Redis 键前缀。 */
    private static final String KEY_PREFIX = "agent:conversation:";
    /** 执行 List 读取、裁剪和过期设置的 Redis 客户端。 */
    private final StringRedisTemplate redisTemplate;

    public RedisConversationMemory(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 反序列化完整有界列表并格式化为模型上下文；Redis 异常时返回原消息。 */
    @Override
    public String contextualize(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return message;
        }
        try {
            List<String> raw = redisTemplate.opsForList().range(key(sessionId), 0, -1);
            if (raw == null || raw.isEmpty()) {
                return message;
            }
            List<ConversationMessage> history = raw.stream()
                    .map(item -> JSONUtil.toBean(item, ConversationMessage.class))
                    .toList();
            return ConversationContextFormatter.format(history, message);
        } catch (Exception ignored) {
            return message;
        }
    }

    /**
     * 追加一轮问答、裁剪旧消息并刷新会话 TTL。
     *
     * <p>这是尽力写入的辅助记忆，Redis 故障不会使已经完成的 Agent 回答失败。</p>
     */
    @Override
    public void appendTurn(String sessionId, String userMessage, String assistantMessage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String key = key(sessionId);
            redisTemplate.opsForList().rightPush(
                    key,
                    JSONUtil.toJsonStr(new ConversationMessage(
                            "user",
                            ConversationContextFormatter.safe(userMessage)
                    ))
            );
            redisTemplate.opsForList().rightPush(
                    key,
                    JSONUtil.toJsonStr(new ConversationMessage(
                            "assistant",
                            ConversationContextFormatter.safe(assistantMessage)
                    ))
            );
            redisTemplate.opsForList().trim(key, -ConversationContextFormatter.MAX_MESSAGES, -1);
            redisTemplate.expire(key, Duration.ofHours(24));
        } catch (Exception ignored) {
            // Conversation memory is best effort; Redis outages must not break chat.
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
