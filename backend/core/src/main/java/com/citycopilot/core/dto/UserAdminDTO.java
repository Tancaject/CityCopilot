package com.citycopilot.core.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 管理端用户列表返回的数据视图，不包含密码等认证凭据。 */
@Data
public class UserAdminDTO {
    /** 用户主键。 */
    private Long id;
    /** 登录手机号。 */
    private String phone;
    /** 展示昵称。 */
    private String nickName;
    /** 头像地址。 */
    private String icon;
    /** 当前角色。 */
    private String role;
    /** 用户创建时间。 */
    private LocalDateTime createTime;
    /** 用户最近更新时间。 */
    private LocalDateTime updateTime;
}
