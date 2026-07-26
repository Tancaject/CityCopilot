ALTER TABLE coupon_order
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN active_order_marker TINYINT
        GENERATED ALWAYS AS (CASE WHEN status IN (4, 6) THEN NULL ELSE 1 END) STORED,
    ADD UNIQUE KEY uk_coupon_order_active_user_voucher (user_id, voucher_id, active_order_marker);

CREATE TABLE seckill_reservation (
    order_id BIGINT NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    stream_record_id VARCHAR(64) NULL,
    failure_code VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    KEY idx_seckill_reservation_status_updated (status, updated_at),
    KEY idx_seckill_reservation_voucher (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Redis预扣与MySQL订单之间的可靠Reservation';

CREATE TABLE outbox_event (
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    schema_version INT NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    source_service VARCHAR(64) NOT NULL,
    trace_id VARCHAR(128) NULL,
    correlation_id VARCHAR(128) NULL,
    causation_id VARCHAR(128) NULL,
    topic VARCHAR(128) NOT NULL,
    tag VARCHAR(64) NULL,
    payload_json LONGTEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    claim_token CHAR(36) NULL,
    fencing_token BIGINT NULL,
    claimed_until TIMESTAMP(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1024) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_outbox_ready (status, next_attempt_at, created_at),
    KEY idx_outbox_claim (claim_token, claimed_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Transactional Outbox';

CREATE TABLE processed_event (
    consumer_group VARCHAR(128) NOT NULL,
    event_id CHAR(36) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    quarantine_reason VARCHAR(512) NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group, event_id),
    KEY idx_processed_event_status (status, processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Consumer Inbox and quarantine';

CREATE TABLE event_projection_version (
    consumer_group VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    current_version BIGINT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group, aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Monotonic consumer aggregate version guard';

CREATE TABLE outbox_fence (
    fence_name VARCHAR(64) NOT NULL,
    current_token BIGINT NOT NULL,
    PRIMARY KEY (fence_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO outbox_fence(fence_name, current_token)
VALUES ('commerce-outbox', 0)
ON DUPLICATE KEY UPDATE current_token = current_token;
