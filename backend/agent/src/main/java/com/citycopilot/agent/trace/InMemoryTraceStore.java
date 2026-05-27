package com.citycopilot.agent.trace;

import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTraceStore implements TraceStore {
    private final Map<String, AgentTrace> traces = new ConcurrentHashMap<>();

    @Override
    public void save(AgentTrace trace) {
        traces.put(trace.traceId(), trace);
    }

    @Override
    public Optional<AgentTrace> find(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }

    @Override
    public List<AgentTrace> listRecent(int limit) {
        return traces.values().stream()
                .sorted(Comparator.comparing(AgentTrace::createdAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }
}
