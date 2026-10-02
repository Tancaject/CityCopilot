CREATE TABLE IF NOT EXISTS agent_user_preference (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    preference_type VARCHAR(32) NOT NULL,
    preference_key CHAR(64) NOT NULL,
    preference_value VARCHAR(128) NOT NULL,
    source VARCHAR(24) NOT NULL DEFAULT 'EXPLICIT_CHAT',
    mention_count INT UNSIGNED NOT NULL DEFAULT 1,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_user_preference (user_id, preference_type, preference_key),
    KEY idx_agent_user_preference_recent (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
