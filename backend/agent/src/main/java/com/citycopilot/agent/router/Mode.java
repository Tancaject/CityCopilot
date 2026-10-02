package com.citycopilot.agent.router;

/** Agent 对话的两种生产执行模式。 */
public enum Mode {
    /** 单步循环，适合普通查询和简单工具调用。 */
    REACT,
    /** 显式规划、逐步执行并支持高风险确认。 */
    PLAN_EXECUTE
}
