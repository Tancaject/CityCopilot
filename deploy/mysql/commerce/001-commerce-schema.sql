SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_voucher (
    id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    sub_title VARCHAR(255) NULL,
    rules VARCHAR(1024) NULL,
    pay_value INT NOT NULL,
    actual_value INT NOT NULL,
    type TINYINT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    version INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_commerce_voucher_shop (shop_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_seckill_voucher (
    voucher_id BIGINT NOT NULL,
    stock INT NOT NULL,
    begin_time TIMESTAMP(3) NOT NULL,
    end_time TIMESTAMP(3) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (voucher_id),
    CONSTRAINT fk_commerce_seckill_voucher FOREIGN KEY (voucher_id) REFERENCES commerce_voucher(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS shop_summary_projection (
    shop_id BIGINT NOT NULL,
    shop_name VARCHAR(255) NOT NULL,
    owner_user_id BIGINT NULL,
    source_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_order (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    shop_name_snapshot VARCHAR(255) NOT NULL,
    voucher_title_snapshot VARCHAR(255) NOT NULL,
    voucher_sub_title_snapshot VARCHAR(255) NULL,
    voucher_rules_snapshot VARCHAR(1024) NULL,
    pay_value_snapshot INT NOT NULL,
    actual_value_snapshot INT NOT NULL,
    pay_type TINYINT NULL,
    status TINYINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    pay_time TIMESTAMP(3) NULL,
    use_time TIMESTAMP(3) NULL,
    refund_time TIMESTAMP(3) NULL,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_commerce_order_user_voucher (user_id, voucher_id),
    UNIQUE KEY uk_commerce_order_user_idempotency (user_id, idempotency_key),
    KEY idx_commerce_order_user_created (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_reservation (
    order_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    stream_record_id VARCHAR(128) NULL,
    failure_code VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_commerce_reservation_user_voucher (user_id, voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_outbox_event (
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    topic VARCHAR(128) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
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
    UNIQUE KEY uk_commerce_outbox_aggregate_version (
        aggregate_type,aggregate_id,event_type,aggregate_version
    ),
    KEY idx_commerce_outbox_ready (status, next_attempt_at),
    KEY idx_commerce_outbox_claim (status, claimed_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_id_segment (
    namespace VARCHAR(32) NOT NULL,
    next_value BIGINT NOT NULL,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (namespace)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_inbox_event (
    consumer_group VARCHAR(128) NOT NULL,
    event_id CHAR(36) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_dead_letter (
    source_id VARCHAR(191) NOT NULL,
    payload_json LONGTEXT NULL,
    error_message VARCHAR(1024) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    replayed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_order_recovery (
    source_id VARCHAR(191) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    order_id BIGINT NULL,
    voucher_id BIGINT NULL,
    user_id BIGINT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NULL,
    payload_json LONGTEXT NULL,
    phase VARCHAR(32) NOT NULL,
    claim_token CHAR(36) NULL,
    claim_owner VARCHAR(191) NULL,
    claim_version BIGINT NOT NULL DEFAULT 0,
    claimed_until TIMESTAMP(3) NULL,
    inventory_fence_token CHAR(36) NULL,
    compensation_delta INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1024) NULL,
    terminal_at TIMESTAMP(3) NULL,
    finalized_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (source_id),
    KEY idx_commerce_recovery_phase (phase, claimed_until),
    KEY idx_commerce_recovery_voucher (voucher_id, phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS commerce_admission_ledger (
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    voucher_id BIGINT NOT NULL,
    request_fingerprint VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    routing_generation VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    physical_shard_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    rejection_code VARCHAR(64) NULL,
    rejection_http_status SMALLINT NULL,
    rejection_message VARCHAR(255) NULL,
    attempts INT NOT NULL DEFAULT 0,
    claim_token CHAR(36) NULL,
    claim_owner VARCHAR(191) NULL,
    claimed_until TIMESTAMP(3) NULL,
    accepted_at TIMESTAMP(3) NULL,
    terminal_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1024) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_commerce_admission_user_idempotency (user_id, idempotency_key),
    KEY idx_commerce_admission_recovery (
        routing_generation, physical_shard_count, status, accepted_at, claimed_until
    ),
    CONSTRAINT chk_commerce_admission_status CHECK (
        status IN ('PREPARED','ACCEPTED','REJECTED','ORDER_CREATED','REPAIR_REQUIRED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO shop_summary_projection(shop_id,shop_name,owner_user_id,source_version)
VALUES (1,'Commerce Migration Demo Shop',1,1);

INSERT IGNORE INTO commerce_voucher(
    id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status
) VALUES (100,1,'Commerce Seckill Demo','Independent transaction service','One voucher per user',8000,5000,1,1);

INSERT IGNORE INTO commerce_seckill_voucher(voucher_id,stock,begin_time,end_time)
VALUES (100,100,CURRENT_TIMESTAMP(3)-INTERVAL 1 DAY,CURRENT_TIMESTAMP(3)+INTERVAL 30 DAY);

INSERT INTO commerce_id_segment(namespace,next_value)
SELECT 'order',COALESCE(MAX(id),0) FROM commerce_order
ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));

INSERT INTO commerce_id_segment(namespace,next_value)
SELECT 'voucher',COALESCE(MAX(id),0) FROM commerce_voucher
ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));
