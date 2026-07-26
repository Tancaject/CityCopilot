package com.citycopilot.contract.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    String name();

    String description();

    String[] requiredPermissions() default {};

    int rateLimitPerMinute() default 60;

    boolean idempotent() default true;

    ToolAudience audience() default ToolAudience.ALL;

    ToolSideEffect sideEffect() default ToolSideEffect.NONE;

    ToolRiskLevel risk() default ToolRiskLevel.LOW;

    ToolApprovalPolicy approval() default ToolApprovalPolicy.NONE;

    String scope() default "core";

    String[] optionalParameters() default {};

    int contractVersion() default 1;
}
