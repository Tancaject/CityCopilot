package com.citycopilot.agent.memory;

/**
 * 从用户明确表述中提取的待持久化偏好。
 *
 * @param type 偏好种类
 * @param key 同类偏好的稳定去重键
 * @param value 写入数据库并用于推荐提示的规范值
 */
record ExplicitPreference(PreferenceType type, String key, String value) {
}
