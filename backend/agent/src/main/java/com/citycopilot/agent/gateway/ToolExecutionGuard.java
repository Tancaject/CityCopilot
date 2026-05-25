package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.ToolMetadata;

import java.util.concurrent.Callable;

@FunctionalInterface
public interface ToolExecutionGuard {
    Object execute(ToolMetadata tool, Callable<Object> invocation) throws Exception;

    static ToolExecutionGuard direct() {
        return (tool, invocation) -> invocation.call();
    }
}
