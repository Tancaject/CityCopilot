package com.citycopilot.agent.audit;

import com.citycopilot.agent.gateway.ToolAuditSink;
import com.citycopilot.agent.gateway.ToolCallAudit;
import com.citycopilot.core.service.OperationLogService;
import org.springframework.stereotype.Component;

@Component
public class OperationLogToolAuditSink implements ToolAuditSink {
    private final OperationLogService operationLogService;

    public OperationLogToolAuditSink(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Override
    public void record(ToolCallAudit audit) {
        operationLogService.record(
                "agent",
                "tool_call",
                "agent_tool",
                null,
                audit.toolName(),
                audit.args(),
                audit.success() ? "ok" : audit.error(),
                audit.success(),
                audit.error() == null ? null : audit.error().message()
        );
    }
}
