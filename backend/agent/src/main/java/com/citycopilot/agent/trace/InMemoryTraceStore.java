package com.citycopilot.agent.trace;

import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例 Trace 存储，用于本地开发和测试。
 *
 * <p>按 traceId 覆盖最新快照，进程重启后丢失且不跨实例共享。</p>
 */
public final class InMemoryTraceStore implements TraceStore {
    /** 轨迹 id 到最新快照的并发映射。 */
    private final Map<String, AgentTrace> traces = new ConcurrentHashMap<>();

    /** 保存或覆盖同一 traceId 的最新轨迹。 */
    @Override
    public void save(AgentTrace trace) {
        traces.put(trace.traceId(), trace);
    }

    /** @return 当前 JVM 中的轨迹 */
    @Override
    public Optional<AgentTrace> find(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }

    /** @return 按创建时间倒序的有限条轨迹 */
    @Override
    public List<AgentTrace> listRecent(int limit) {
        return traces.values().stream()
                .sorted(Comparator.comparing(AgentTrace::createdAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }
}
