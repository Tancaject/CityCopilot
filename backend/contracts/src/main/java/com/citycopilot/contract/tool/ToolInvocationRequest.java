package com.citycopilot.contract.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core 工具执行端接收的统一调用请求。
 *
 * @param arguments 按参数名组织的工具实参
 * @param requestId 跨服务请求标识，保留供协议追踪；幂等以请求头中的 Idempotency-Key 为准
 * @param expectedVersion 调用方审阅的工具契约版本；缺失仅代表旧协议版本 1，不能自动采用服务端最新版
 */
public record ToolInvocationRequest(Map<String, Object> arguments, String requestId, Integer expectedVersion) {
    public ToolInvocationRequest(Map<String, Object> arguments, String requestId) {
        this(arguments, requestId, null);
    }
    /** 将实参规范化为保持插入顺序的不可变快照。 */
    public ToolInvocationRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
    }
}
