package com.citycopilot.core.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明控制器类或方法所需权限，由 MVC 拦截器在执行前检查。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    /** @return 所需权限码 */
    String[] value();

    /** @return true 表示满足任一权限即可，false 表示必须全部满足 */
    boolean anyOf() default false;
}
