package com.citycopilot.contract.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记可暴露给 Agent 或 MCP 的 Core 方法，并声明其调用合同与安全元数据。
 *
 * <p>工具扫描器读取该运行时注解生成 Tool Schema，Agent/MCP 据此做受众和审批治理；
 * Core 执行入口会独立校验权限、参数以及非幂等工具的幂等键。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    /** @return 模型调用时使用的稳定工具名 */
    String name();

    /** @return 提供给模型的能力说明 */
    String description();

    /** @return 调用该工具必须具备的权限码 */
    String[] requiredPermissions() default {};

    /** @return 接入侧针对该工具执行的分钟调用上限 */
    int rateLimitPerMinute() default 60;

    /** @return 工具自身能否安全重复执行；为 false 时 Core 要求提供幂等键 */
    boolean idempotent() default true;

    /** @return 允许发现并调用该工具的客户端范围 */
    ToolAudience audience() default ToolAudience.ALL;

    /** @return 工具对系统状态的影响类型 */
    ToolSideEffect sideEffect() default ToolSideEffect.NONE;

    /** @return 工具的业务风险等级 */
    ToolRiskLevel risk() default ToolRiskLevel.LOW;

    /** @return 执行工具前需要满足的审批策略 */
    ToolApprovalPolicy approval() default ToolApprovalPolicy.NONE;

    /** @return 工具所属的服务或能力域 */
    String scope() default "core";

    /** @return 即使 Java 参数非空，协议层仍允许省略的参数名 */
    String[] optionalParameters() default {};

    /** @return 工具合同版本，用于客户端兼容判断 */
    int contractVersion() default 1;
}
