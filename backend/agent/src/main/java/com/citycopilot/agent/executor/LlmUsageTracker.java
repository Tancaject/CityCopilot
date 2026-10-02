package com.citycopilot.agent.executor;

import cn.hutool.json.JSONUtil;
import org.springframework.web.client.RestClientResponseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Request-thread accounting reports provider usage as received, and distinguishes fallback from successful model calls. */
public final class LlmUsageTracker {
    private static final ThreadLocal<LlmUsageTracker> CURRENT = new ThreadLocal<>();
    private long inputTokens, outputTokens;
    private int usageResponses, calls, successfulCalls, fallbackCalls;
    private String model;
    private final Map<String, Integer> providerErrors = new LinkedHashMap<>();
    private LlmUsageTracker() {}
    public static void begin() { CURRENT.set(new LlmUsageTracker()); }
    public static void clear() { CURRENT.remove(); }
    static void response(Map<?, ?> body) {
        var current = CURRENT.get();
        if (current == null || body == null || !(body.get("usage") instanceof Map<?, ?> usage)) return;
        if (usage.get("prompt_tokens") instanceof Number input && usage.get("completion_tokens") instanceof Number output) {
            current.inputTokens += input.longValue(); current.outputTokens += output.longValue(); current.usageResponses++;
        }
    }
    static void outcome(String model, String outcome) {
        var current = CURRENT.get();
        if (current == null) return;
        current.model = model; current.calls++;
        if (outcome.equals("success")) current.successfulCalls++; else current.fallbackCalls++;
    }
    static void failure(Exception error) {
        var current = CURRENT.get();
        if (current == null) return;
        String code = error.getClass().getSimpleName();
        if (error instanceof RestClientResponseException response) {
            code = "HTTP_" + response.getStatusCode().value();
            try {
                var details = JSONUtil.parseObj(response.getResponseBodyAsString()).getJSONObject("error");
                String providerCode = details == null ? null : details.getStr("code");
                if (providerCode != null && providerCode.matches("[A-Za-z0-9_.-]{1,80}")) code += ":" + providerCode;
            } catch (RuntimeException ignored) {
                // Error text may echo credentials; only a bounded protocol code may enter user-visible evidence.
            }
        }
        current.providerErrors.merge(code, 1, Integer::sum);
    }
    public static String warning() {
        var current = CURRENT.get();
        return current != null && current.fallbackCalls > 0
                ? "AI 模型服务本轮未能完整响应。已返回的业务结果保留，请在对应业务页面核实当前状态后继续。" : null;
    }
    public static Map<String, Object> snapshot() {
        var current = CURRENT.get();
        if (current == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", current.model); result.put("calls", current.calls);
        result.put("successfulCalls", current.successfulCalls); result.put("fallbackCalls", current.fallbackCalls);
        result.put("usageResponses", current.usageResponses);
        result.put("providerErrors", Map.copyOf(current.providerErrors));
        result.put("usageComplete", current.usageResponses > 0 && current.usageResponses == current.calls && current.fallbackCalls == 0);
        result.put("inputTokens", current.usageResponses == 0 ? null : current.inputTokens);
        result.put("outputTokens", current.usageResponses == 0 ? null : current.outputTokens);
        return result;
    }
}
