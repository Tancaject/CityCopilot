package com.citycopilot.agent.http;

import java.util.Map;

public final class AgentPingHandler {
    public Map<String, Object> ping() {
        return Map.of(
                "application", "city-copilot",
                "status", "ok"
        );
    }
}
