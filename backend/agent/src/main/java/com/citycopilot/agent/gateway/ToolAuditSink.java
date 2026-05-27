package com.citycopilot.agent.gateway;

@FunctionalInterface
public interface ToolAuditSink {
    void record(ToolCallAudit audit);

    static ToolAuditSink noop() {
        return audit -> {
        };
    }
}
