package com.citycopilot.contract.tool;

/** 工具对系统状态的副作用类型。 */
public enum ToolSideEffect {
    /** 不修改业务状态。 */
    NONE,
    /** 会写入状态，但重复执行不会产生额外业务效果。 */
    IDEMPOTENT_WRITE,
    /** 会写入状态，重复执行可能产生额外影响。 */
    NON_IDEMPOTENT_WRITE
}
