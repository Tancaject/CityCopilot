package com.citycopilot.contract.security;

import java.util.Set;

/**
 * 服务间调用携带的最小用户身份快照。
 *
 * @param id 登录用户主键，匿名请求为 {@code null}
 * @param nickName 用户展示名
 * @param icon 用户头像地址
 * @param role 用户角色
 * @param permissions 已授予的权限码集合
 * @param authenticated 是否已经通过身份认证
 */
public record ServiceUser(
        Long id,
        String nickName,
        String icon,
        String role,
        Set<String> permissions,
        boolean authenticated
) {
    /**
     * 将权限集合规范化为不可变非空集合，防止跨模块传递后被修改。
     */
    public ServiceUser {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /**
     * 创建未认证身份，供缺少用户上下文的内部调用显式降级。
     *
     * @return 不包含用户资料与权限的匿名身份
     */
    public static ServiceUser anonymous() {
        return new ServiceUser(null, null, null, null, Set.of(), false);
    }

    /**
     * 检查当前身份是否拥有指定权限；通配权限 {@code *} 可通过所有检查。
     *
     * @param permission 待检查的权限码
     * @return 拥有具体权限或通配权限时返回 {@code true}
     */
    public boolean hasPermission(String permission) {
        return permissions.contains("*") || permissions.contains(permission);
    }
}
