package com.citycopilot.contract.tool;

/** 工具对用户或系统造成影响的风险等级。 */
public enum ToolRiskLevel {
    /** 只读或影响可忽略。 */
    LOW,
    /** 会修改状态，但通常可恢复或影响范围有限。 */
    MEDIUM,
    /** 高影响、敏感或难以恢复的操作。 */
    HIGH
}
