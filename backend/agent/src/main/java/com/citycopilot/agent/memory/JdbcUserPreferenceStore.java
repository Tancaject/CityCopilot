package com.citycopilot.agent.memory;

import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 使用关系数据库保存长期偏好的 JDBC 实现。
 *
 * <p>以偏好类型和规范键的 SHA-256 作为用户内唯一键，既支持单值覆盖与多值共存，也避免把自由文本键直接写入索引。</p>
 */
@Repository
public class JdbcUserPreferenceStore implements UserPreferenceStore {
    /** 防止偏好查询无界增长的最大条数。 */
    private static final int MAX_LIST_SIZE = 50;

    /** 执行参数化 SQL 的 Spring JDBC 客户端。 */
    private final JdbcTemplate jdbcTemplate;

    public JdbcUserPreferenceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新偏好插入，重复偏好更新值、提及次数和时间。 */
    @Override
    public void upsert(long userId, ExplicitPreference preference) {
        jdbcTemplate.update("""
                INSERT INTO agent_user_preference(
                    user_id, preference_type, preference_key, preference_value
                ) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    preference_value=VALUES(preference_value),
                    mention_count=mention_count+1,
                    updated_at=CURRENT_TIMESTAMP(3)
                """,
                userId,
                preference.type().name(),
                DigestUtil.sha256Hex(preference.type().name() + ":" + preference.key()),
                preference.value()
        );
    }

    /** 按最近更新时间倒序读取，并将调用方 limit 限制在安全范围。 */
    @Override
    public List<UserPreference> list(long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIST_SIZE));
        return jdbcTemplate.query("""
                SELECT id, user_id, preference_type, preference_value, mention_count, created_at, updated_at
                FROM agent_user_preference
                WHERE user_id=?
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new UserPreference(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        PreferenceType.valueOf(rs.getString("preference_type")),
                        rs.getString("preference_value"),
                        rs.getInt("mention_count"),
                        timestampToInstant(rs.getTimestamp("created_at")),
                        timestampToInstant(rs.getTimestamp("updated_at"))
                ),
                userId,
                safeLimit
        );
    }

    /** 以主键和 userId 联合删除，阻止跨用户遗忘。 */
    @Override
    public boolean delete(long userId, long preferenceId) {
        return jdbcTemplate.update(
                "DELETE FROM agent_user_preference WHERE id=? AND user_id=?",
                preferenceId,
                userId
        ) == 1;
    }

    /** 按提取器使用的规范键删除指定类别偏好。 */
    @Override
    public int deleteByKey(long userId, PreferenceType type, String key) {
        return jdbcTemplate.update(
                "DELETE FROM agent_user_preference WHERE user_id=? AND preference_type=? AND preference_key=?",
                userId,
                type.name(),
                DigestUtil.sha256Hex(type.name() + ":" + key)
        );
    }

    @Override
    public int deleteAll(long userId) {
        return jdbcTemplate.update("DELETE FROM agent_user_preference WHERE user_id=?", userId);
    }

    private java.time.Instant timestampToInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
