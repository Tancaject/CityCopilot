ALTER TABLE outbox_event
    MODIFY COLUMN payload_json LONGTEXT NOT NULL;

ALTER TABLE outbox_event
    ADD CONSTRAINT chk_outbox_payload_json CHECK (JSON_VALID(payload_json));
