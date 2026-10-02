package com.citycopilot.agent.http;

/**
 * Agent 推送给前端的协议无关 SSE 事件。
 *
 * @param event 事件类型，如 traceId、tool、confirmation、delta、final 或 error
 * @param data 可序列化的事件载荷
 */
public record AgentSseEvent(String event, Object data) {
}
