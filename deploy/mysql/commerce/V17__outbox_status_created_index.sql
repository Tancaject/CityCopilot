-- The oldest-unpublished-event gauge takes MIN(created_at) per unpublished status on every metrics scrape.
-- Without (status, created_at) it reads every unpublished row, so the gauge slows down exactly when a backlog builds.
CREATE INDEX idx_commerce_outbox_status_created
    ON commerce_outbox_event(status, created_at);
