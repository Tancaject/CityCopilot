package com.citycopilot.core.dto;

import java.time.LocalDateTime;

/** 笔记读取契约；作者信息和点赞状态仅在原有聚合查询中补充。 */
public record BlogView(
        Long id,
        Long shopId,
        Long userId,
        String icon,
        String name,
        Boolean isLike,
        String title,
        String images,
        String content,
        Integer liked,
        Integer comments,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
