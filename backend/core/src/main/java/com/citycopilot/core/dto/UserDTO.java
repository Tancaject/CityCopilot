package com.citycopilot.core.dto;

import lombok.Data;

import java.util.Set;

/**
 * 保存到登录会话并跨拦截器传递的最小用户快照。
 *
 * <p>只保留页面展示和授权所需字段，避免把密码等完整用户实体放入 Redis/ThreadLocal。</p>
 */
@Data
public class UserDTO {
    /** 用户主键。 */
    private Long id;
    /** 展示昵称。 */
    private String nickName;
    /** 头像地址。 */
    private String icon;
    /** 用户角色。 */
    private String role;
    /** 当前会话解析出的权限码。 */
    private Set<String> permissions;
}
