package com.citycopilot.agent.tool;

import com.citycopilot.agent.gateway.ToolExecutionException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The contract reviewed with an approval; missing snapshots belong to the legacy version 1 protocol. */
public record ToolContractSnapshot(int contractVersion, Map<String, Object> resultSchema) {
    public static final String MISMATCH = "tool_contract_mismatch";

    public ToolContractSnapshot {
        if (contractVersion < 1) throw new IllegalArgumentException("Tool contract version must be positive");
        resultSchema = immutableMap(resultSchema == null ? Map.of() : resultSchema);
    }

    public static ToolContractSnapshot of(ToolMetadata tool) {
        return new ToolContractSnapshot(tool.contractVersion(), tool.resultSchema());
    }

    public static int expectedVersion(ToolContractSnapshot snapshot) {
        return snapshot == null ? 1 : snapshot.contractVersion();
    }

    public static ToolMetadata requireCompatible(String name, ToolContractSnapshot approved, List<ToolMetadata> catalog) {
        ToolMetadata current = catalog.stream().filter(tool -> tool.name().equals(name)).findFirst().orElse(null);
        if (current == null || current.contractVersion() != expectedVersion(approved)
                || approved != null && !approved.resultSchema().equals(current.resultSchema())) {
            throw new ToolExecutionException(MISMATCH,
                    "工具契约已变化或不可用：" + name + "。请重新审阅计划后确认；本次未执行该工具。", false);
        }
        return current;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutable(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, immutable(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(ToolContractSnapshot::immutable).toList();
        return value;
    }
}
