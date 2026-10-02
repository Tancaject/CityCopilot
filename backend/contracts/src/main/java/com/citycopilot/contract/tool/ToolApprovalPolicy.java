package com.citycopilot.contract.tool;

/** 工具执行前所需的审批级别。 */
public enum ToolApprovalPolicy {
    /** 无需额外审批。 */
    NONE,
    /** 必须由当前用户确认。 */
    USER_CONFIRMATION,
    /** 必须由运营人员审批。 */
    OPS_APPROVAL
}
