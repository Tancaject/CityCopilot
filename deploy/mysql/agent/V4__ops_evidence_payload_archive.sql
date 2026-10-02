CREATE TABLE ops_evidence_payload_archive (
    evidence_id CHAR(36) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    archived_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (evidence_id),
    CONSTRAINT chk_ops_evidence_archive_json CHECK (JSON_VALID(payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE ops_evidence
    MODIFY payload_json LONGTEXT NULL,
    ADD payload_archived_at TIMESTAMP(3) NULL,
    ADD CONSTRAINT chk_ops_evidence_payload_location CHECK (
        (payload_json IS NOT NULL AND payload_archived_at IS NULL)
        OR (payload_json IS NULL AND payload_archived_at IS NOT NULL)
    ),
    ADD INDEX idx_ops_evidence_archive (case_id, payload_archived_at, expires_at, id);
