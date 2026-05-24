package com.citycopilot.core.agent;

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
}
