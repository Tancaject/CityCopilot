package com.citycopilot.agent.security;

import com.citycopilot.contract.security.ServiceUser;

/**
 * 单个 Agent HTTP 请求的可信安全上下文。
 *
 * @param user 由 Core 会话接口解析的服务用户
 * @param authorization 待转发 Core 的原始用户授权头
 * @param requestId 跨服务请求号
 */
public record AgentRequestContext(ServiceUser user, String authorization, String requestId) {
    /** @return 保留请求号但不携带授权的匿名上下文 */
    public static AgentRequestContext anonymous(String requestId) {
        return new AgentRequestContext(ServiceUser.anonymous(), null, requestId);
    }
}
