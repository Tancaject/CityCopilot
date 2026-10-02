-- Core owns attribution and rebuildable features; Commerce remains the booking authority.
CREATE TABLE IF NOT EXISTS search_impression (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    candidate_ids JSON NOT NULL,
    ranking_version VARCHAR(64) NOT NULL,
    issued_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    KEY idx_search_impression_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_behavior_event (
    event_id VARCHAR(128) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    impression_id CHAR(36) NULL,
    ranking_version VARCHAR(64) NULL,
    candidate_position INT NULL,
    attributed BOOLEAN NOT NULL DEFAULT FALSE,
    booking_id CHAR(36) NULL,
    aggregate_version BIGINT NULL,
    occurred_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    cleared_at DATETIME(3) NULL,
    CONSTRAINT chk_behavior_origin CHECK (origin IN ('CLIENT','COMMERCE')),
    KEY idx_behavior_user_shop (user_id,shop_id,occurred_at),
    KEY idx_behavior_expiry (expires_at),
    KEY idx_behavior_booking (booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_preference_feature (
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    views BIGINT NOT NULL DEFAULT 0,
    clicks BIGINT NOT NULL DEFAULT 0,
    favorites BIGINT NOT NULL DEFAULT 0,
    confirmed BIGINT NOT NULL DEFAULT 0,
    used BIGINT NOT NULL DEFAULT 0,
    cancelled BIGINT NOT NULL DEFAULT 0,
    no_shows BIGINT NOT NULL DEFAULT 0,
    negative BOOLEAN NOT NULL DEFAULT FALSE,
    last_occurred_at DATETIME(3) NULL,
    rebuilt_at DATETIME(3) NULL,
    refresh_after DATETIME(3) NULL,
    PRIMARY KEY(user_id,shop_id),
    KEY idx_preference_feature_refresh (refresh_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS booking_behavior_projection (
    booking_id CHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    current_status VARCHAR(24) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    schedule_version BIGINT NOT NULL,
    impression_id CHAR(36) NULL,
    ranking_version VARCHAR(64) NULL,
    candidate_position INT NULL,
    attributed BOOLEAN NOT NULL DEFAULT FALSE,
    occurred_at DATETIME(3) NOT NULL,
    KEY idx_booking_behavior_user_shop (user_id,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
