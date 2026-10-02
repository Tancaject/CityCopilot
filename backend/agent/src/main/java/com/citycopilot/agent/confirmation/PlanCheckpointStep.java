package com.citycopilot.agent.confirmation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可持久化的单个计划工具步骤。
 *
 * @param idx 面向用户与 Trace 的步骤编号
 * @param toolName 工具注册名
 * @param args 工具参数快照
 * @param highRisk 是否必须暂停并取得用户确认
 */
public record PlanCheckpointStep(
        int idx,
        String toolName,
        Map<String, Object> args,
        boolean highRisk
) {
    /** 固化参数副本，保证后续哈希与恢复执行使用同一份内容。 */
    public PlanCheckpointStep {
        args = args == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(args));
    }
}
