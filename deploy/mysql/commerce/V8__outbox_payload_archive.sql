-- Keep the original event and aggregate uniqueness constraints online after payload archival.
CREATE TABLE commerce_outbox_payload_archive (
    event_id CHAR(36) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    archived_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE commerce_outbox_event
    MODIFY payload_json LONGTEXT NULL,
    ADD payload_archived_at TIMESTAMP(3) NULL,
    ADD CONSTRAINT chk_commerce_outbox_payload_location CHECK (
        (payload_json IS NOT NULL AND payload_archived_at IS NULL)
        OR (payload_json IS NULL AND payload_archived_at IS NOT NULL)
    ),
    ADD INDEX idx_commerce_outbox_archive (status, payload_archived_at, published_at, event_id);
