package com.citycopilot.agent.gateway;

import java.util.Set;

/**
 * 一次工具调用携带的安全与链路上下文。
 *
 * @param userId 当前登录用户标识
 * @param permissions 已由可信会话解析出的权限集合
 * @param authorization 转发给 Core 的原始用户授权头
 * @param requestId 跨服务排障请求号
 * @param idempotencyKey 写操作去重键
 * @param expectedVersion 已绑定的工具契约版本，空值由普通调用绑定当前目录版本
 */
public record ToolCallContext(
        String userId,
        Set<String> permissions,
        String authorization,
        String requestId,
        String idempotencyKey,
        Integer expectedVersion
) {
    public ToolCallContext(String userId, Set<String> permissions, String authorization, String requestId, String idempotencyKey) {
        this(userId, permissions, authorization, requestId, idempotencyKey, null);
    }
    public ToolCallContext(String userId, Set<String> permissions) {
        this(userId, permissions, null, null, null);
    }

    /** 固化权限集合，防止调用期间被外部修改。 */
    public ToolCallContext {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** @return 拥有指定权限或通配权限时为 true */
    public boolean hasPermission(String permission) {
        return permissions != null && (permissions.contains("*") || permissions.contains(permission));
    }

    public ToolCallContext withExpectedVersion(int version) {
        return new ToolCallContext(userId, permissions, authorization, requestId, idempotencyKey, version);
    }

    /** @return 保留身份与链路字段、替换幂等键的新上下文 */
    public ToolCallContext withIdempotencyKey(String value) {
        return new ToolCallContext(userId, permissions, authorization, requestId, value, expectedVersion);
    }
}
