package com.citycopilot.core.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserAdminDTO {
    private Long id;
    private String phone;
    private String nickName;
    private String icon;
    private String role;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
