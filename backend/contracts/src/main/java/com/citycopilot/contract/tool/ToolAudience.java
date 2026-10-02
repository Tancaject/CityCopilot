package com.citycopilot.contract.tool;

/** 工具可被哪类调用方发现和使用。 */
public enum ToolAudience {
    /** 面向普通消费者会话。 */
    CONSUMER,
    /** 面向运营后台。 */
    OPS,
    /** 面向 MCP 客户端。 */
    MCP,
    /** 对所有受支持的调用方开放。 */
    ALL
}
