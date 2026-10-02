package com.citycopilot.agent.memory;

import cn.hutool.crypto.digest.DigestUtil;
import com.citycopilot.contract.security.ServiceUser;

/**
 * 将前端会话标识绑定到已登录用户的作用域工具。
 *
 * <p>Redis 和内存存储只看到哈希键，避免不同用户复用相同 conversationId 时串读历史，也不直接暴露原始会话标识。</p>
 */
public final class ConversationScope {

    private ConversationScope() {
    }

    /**
     * 构建用户隔离的会话键。
     *
     * @param user Core 验证后的服务用户
     * @param sessionId 前端提供的会话标识
     * @return SHA-256 会话键；匿名、无用户 id 或空会话时返回 null 以禁用记忆
     */
    public static String sessionKey(ServiceUser user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (user == null || !user.authenticated() || user.id() == null) {
            return null;
        }
        String owner = "user:" + user.id();
        return DigestUtil.sha256Hex(owner + ":" + sessionId);
    }
}
