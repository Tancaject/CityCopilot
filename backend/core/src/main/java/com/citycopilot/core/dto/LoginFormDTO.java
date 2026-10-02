package com.citycopilot.core.dto;

import lombok.Data;

/** 登录接口接收的手机号、验证码或密码凭据。 */
@Data
public class LoginFormDTO {
    /** 用户手机号，也是主要登录标识。 */
    private String phone;
    /** 短信验证码登录时提交的一次性验证码。 */
    private String code;
    /** 密码登录时提交的原始密码，仅在请求处理期间使用。 */
    private String password;
}
