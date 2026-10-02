package com.citycopilot.agent.memory;

import java.time.Instant;

/**
 * 对外展示的长期偏好记录。
 *
 * @param id 数据库主键
 * @param userId 所属用户
 * @param type 偏好类型
 * @param value 规范化偏好值
 * @param mentionCount 用户重复明确表达的累计次数
 * @param createdAt 首次保存时间
 * @param updatedAt 最近表达时间
 */
public record UserPreference(
        long id,
        long userId,
        PreferenceType type,
        String value,
        int mentionCount,
        Instant createdAt,
        Instant updatedAt
) {
}
