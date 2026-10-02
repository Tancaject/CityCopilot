package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.ToolMetadata;

import java.util.concurrent.Callable;

/**
 * 工具调用的可靠性保护扩展点。
 *
 * <p>生产实现负责限流、熔断、超时与安全重试；测试或纯本地场景可使用直通实现。</p>
 */
@FunctionalInterface
public interface ToolExecutionGuard {
    /**
     * 在工具元数据声明的可靠性边界内执行调用。
     *
     * @param tool 工具风险、限流和幂等元数据
     * @param invocation 延迟执行的实际调用
     * @return 工具原始返回值
     */
    Object execute(ToolMetadata tool, Callable<Object> invocation) throws Exception;

    /** @return 不增加保护策略、直接执行 Callable 的实现 */
    static ToolExecutionGuard direct() {
        return (tool, invocation) -> invocation.call();
    }
}
