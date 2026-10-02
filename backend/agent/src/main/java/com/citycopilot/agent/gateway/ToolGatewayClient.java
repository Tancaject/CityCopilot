package com.citycopilot.agent.gateway;

import com.citycopilot.agent.tool.ToolMetadata;

import java.util.List;
import java.util.Map;

/**
 * Agent 执行器使用的统一工具网关合同。
 *
 * <p>本地反射网关与远程 Core 网关共享此接口，执行器无需感知工具实际部署位置。</p>
 */
public interface ToolGatewayClient {
    /** @return 当前可用于规划和模型 Function Calling 的工具元数据 */
    List<ToolMetadata> tools();

    /** Approval checks require an authoritative refresh; local registries are already authoritative. */
    default List<ToolMetadata> refreshTools() { return tools(); }

    /**
     * 在调用上下文约束下执行一个工具。
     *
     * @param name 工具注册名
     * @param args 结构化参数
     * @param context 用户、权限、授权头、请求号和幂等键
     * @return 成功数据或标准化错误，不直接向执行器抛业务异常
     */
    ToolResult invoke(String name, Map<String, Object> args, ToolCallContext context);
}
