package com.citycopilot.core.dto;

import lombok.Data;

import java.util.Set;

@Data
public class UserDTO {//简化session中保存的信息，提高安全性并减轻服务器压力
    private Long id;
    private String nickName;
    private String icon;
    private String role;
    private Set<String> permissions;
}
