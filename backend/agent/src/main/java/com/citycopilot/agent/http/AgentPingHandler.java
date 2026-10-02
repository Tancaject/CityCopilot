package com.citycopilot.agent.http;

import java.util.Map;

/** 提供不依赖 Core、Redis 或模型的 Agent 进程存活响应。 */
public final class AgentPingHandler {
    /** @return 固定的应用名与存活状态 */
    public Map<String, Object> ping() {
        return Map.of(
                "application", "city-copilot",
                "status", "ok"
        );
    }
}
