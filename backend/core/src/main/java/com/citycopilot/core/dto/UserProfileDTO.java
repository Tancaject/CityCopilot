package com.citycopilot.core.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 扩展资料的公开读取视图；兼容审计时间字段保持 null，不暴露实体维护时间。 */
public record UserProfileDTO(Long userId, String city, String introduce, Integer fans,
                             Integer followee, Boolean gender, LocalDate birthday,
                             Integer credits, Boolean level, LocalDateTime createTime,
                             LocalDateTime updateTime) {}
