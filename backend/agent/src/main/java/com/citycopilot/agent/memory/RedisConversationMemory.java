package com.citycopilot.agent.memory;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

public final class RedisConversationMemory implements ConversationMemory {
    private static final String KEY_PREFIX = "agent:conversation:";
    private static final int MAX_MESSAGES = 12;
    private static final int MAX_CONTEXT_CHARS = 6_000;
    private final StringRedisTemplate redisTemplate;

    public RedisConversationMemory(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

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
            StringBuilder context = new StringBuilder("Conversation history:\n");
            for (String item : raw) {
                ConversationMessage turn = JSONUtil.toBean(item, ConversationMessage.class);
                context.append(turn.role()).append(": ").append(turn.content()).append('\n');
            }
            context.append("Current user: ").append(message == null ? "" : message);
            return context.length() <= MAX_CONTEXT_CHARS
                    ? context.toString()
                    : context.substring(context.length() - MAX_CONTEXT_CHARS);
        } catch (Exception ignored) {
            return message;
        }
    }

    @Override
    public void appendTurn(String sessionId, String userMessage, String assistantMessage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String key = key(sessionId);
            redisTemplate.opsForList().rightPush(key, JSONUtil.toJsonStr(new ConversationMessage("user", safe(userMessage))));
            redisTemplate.opsForList().rightPush(key, JSONUtil.toJsonStr(new ConversationMessage("assistant", safe(assistantMessage))));
            redisTemplate.opsForList().trim(key, -MAX_MESSAGES, -1);
            redisTemplate.expire(key, Duration.ofHours(24));
        } catch (Exception ignored) {
            // Conversation memory is best effort; Redis outages must not break chat.
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
