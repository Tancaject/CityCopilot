package com.citycopilot.agent.gateway;

/** 工具调用审计记录的输出端口。 */
@FunctionalInterface
public interface ToolAuditSink {
    /** 持久化或转发一条脱离执行器实现的工具审计记录。 */
    void record(ToolCallAudit audit);

    /** @return 丢弃审计记录的空实现，供未配置审计后端的环境使用 */
    static ToolAuditSink noop() {
        return audit -> {
        };
    }
}
