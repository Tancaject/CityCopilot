package com.citycopilot.agent.trace;

import java.util.List;
import java.util.Optional;

public interface TraceStore {
    void save(AgentTrace trace);

    Optional<AgentTrace> find(String traceId);

    default List<AgentTrace> listRecent(int limit) {
        return List.of();
    }
}
