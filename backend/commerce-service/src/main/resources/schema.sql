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

CREATE TABLE IF NOT EXISTS commerce_id_segment (
    namespace VARCHAR(32) NOT NULL,
    next_value BIGINT NOT NULL,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (namespace)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
