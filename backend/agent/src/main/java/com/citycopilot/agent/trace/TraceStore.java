package com.citycopilot.agent.trace;

import java.util.List;
import java.util.Optional;

/**
 * Agent Trace 的保存与查询端口。
 *
 * <p>Trace 主要服务于可观测性、SSE 和确认续跑；具体实现可使用共享 Redis 或单机内存。</p>
 */
public interface TraceStore {
    /** 保存或按 traceId 覆盖一条完整轨迹。 */
    void save(AgentTrace trace);

    /** @return 指定轨迹，不存在或存储不可用时为空 */
    Optional<AgentTrace> find(String traceId);

    /** @return 最近轨迹；不支持索引的实现默认返回空列表 */
    default List<AgentTrace> listRecent(int limit) {
        return List.of();
    }
}
