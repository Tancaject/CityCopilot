package com.citycopilot.agent.memory;

import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class JdbcUserPreferenceStore implements UserPreferenceStore {
    private static final int MAX_LIST_SIZE = 50;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserPreferenceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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

    @Override
    public boolean delete(long userId, long preferenceId) {
        return jdbcTemplate.update(
                "DELETE FROM agent_user_preference WHERE id=? AND user_id=?",
                preferenceId,
                userId
        ) == 1;
    }

    @Override
    public int deleteAll(long userId) {
        return jdbcTemplate.update("DELETE FROM agent_user_preference WHERE user_id=?", userId);
    }

    private java.time.Instant timestampToInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
