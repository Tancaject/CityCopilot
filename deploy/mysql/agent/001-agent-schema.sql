CREATE TABLE IF NOT EXISTS ops_event_inbox (
    consumer_group VARCHAR(128) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    quarantine_reason VARCHAR(512) NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group, event_id),
    CONSTRAINT chk_ops_event_payload_json CHECK (JSON_VALID(payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ops_case (
    id CHAR(36) NOT NULL,
    incident_key VARCHAR(191) NOT NULL,
    event_id CHAR(36) NOT NULL,
    case_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    current_step INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    cancellation_requested TINYINT(1) NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128) NULL,
    lease_until TIMESTAMP(3) NULL,
    last_error VARCHAR(1024) NULL,
    next_retry_at TIMESTAMP(3) NULL,
    open_incident_marker TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN status IN (
                'OPEN','COLLECTING_EVIDENCE','MANUAL_REVIEW','PLAN_READY',
                'AWAITING_APPROVAL','APPROVED','EXECUTING','VERIFYING','RETRY_WAIT'
            ) THEN 1 ELSE NULL END
        ) STORED,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    resolved_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_case_event (event_id),
    UNIQUE KEY uk_ops_case_open_incident (incident_key, open_incident_marker),
    KEY idx_ops_case_status_retry (status, next_retry_at, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ops_evidence (
    id CHAR(36) NOT NULL,
    case_id CHAR(36) NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    source VARCHAR(128) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    collected_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ops_evidence_case_expiry (case_id, expires_at),
    CONSTRAINT fk_ops_evidence_case FOREIGN KEY (case_id) REFERENCES ops_case(id),
    CONSTRAINT chk_ops_evidence_payload_json CHECK (JSON_VALID(payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ops_plan (
    id CHAR(36) NOT NULL,
    case_id CHAR(36) NOT NULL,
    plan_version INT NOT NULL,
    source VARCHAR(24) NOT NULL,
    diagnosis_json LONGTEXT NOT NULL,
    steps_json LONGTEXT NOT NULL,
    plan_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    superseded_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_plan_case_version (case_id, plan_version),
    UNIQUE KEY uk_ops_plan_hash (plan_hash),
    CONSTRAINT fk_ops_plan_case FOREIGN KEY (case_id) REFERENCES ops_case(id),
    CONSTRAINT chk_ops_plan_diagnosis_json CHECK (JSON_VALID(diagnosis_json)),
    CONSTRAINT chk_ops_plan_steps_json CHECK (JSON_VALID(steps_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ops_approval (
    id CHAR(36) NOT NULL,
    case_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    plan_hash CHAR(64) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    decided_by VARCHAR(128) NULL,
    decided_at TIMESTAMP(3) NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    consumed_at TIMESTAMP(3) NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_approval_plan (plan_id),
    CONSTRAINT fk_ops_approval_case FOREIGN KEY (case_id) REFERENCES ops_case(id),
    CONSTRAINT fk_ops_approval_plan FOREIGN KEY (plan_id) REFERENCES ops_plan(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ops_execution (
    id CHAR(36) NOT NULL,
    case_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    step_index INT NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments_json LONGTEXT NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json LONGTEXT NULL,
    error_code VARCHAR(128) NULL,
    started_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ops_execution_idempotency (idempotency_key),
    KEY idx_ops_execution_case_step (case_id, step_index),
    CONSTRAINT fk_ops_execution_case FOREIGN KEY (case_id) REFERENCES ops_case(id),
    CONSTRAINT fk_ops_execution_plan FOREIGN KEY (plan_id) REFERENCES ops_plan(id),
    CONSTRAINT chk_ops_execution_arguments_json CHECK (JSON_VALID(arguments_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ops_transition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_id CHAR(36) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(512) NULL,
    evidence_ids_json LONGTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ops_transition_case (case_id, id),
    CONSTRAINT fk_ops_transition_case FOREIGN KEY (case_id) REFERENCES ops_case(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ops_dead_letter (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(191) NOT NULL,
    payload_json LONGTEXT NULL,
    error_message VARCHAR(1024) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    replayed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    KEY idx_ops_dead_letter_open (replayed_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
