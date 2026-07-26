INSERT INTO outbox_event(
    event_id, event_type, schema_version, aggregate_type, aggregate_id,
    aggregate_version, source_service, trace_id, correlation_id, causation_id,
    topic, tag, payload_json, payload_hash, status
) VALUES (
    '00000000-0000-0000-0000-000000000201',
    'OrderCreated',
    1,
    'coupon_order',
    'e2-smoke-order',
    1,
    'core-service',
    'e2-smoke-trace',
    'e2-smoke-correlation',
    NULL,
    'citycopilot-commerce-events-v1',
    'OrderCreated',
    '{"eventId":"00000000-0000-0000-0000-000000000201","eventType":"OrderCreated","schemaVersion":1,"aggregateType":"coupon_order","aggregateId":"e2-smoke-order","aggregateVersion":1,"sourceService":"core-service","occurredAt":"2026-07-26T04:35:00Z","traceId":"e2-smoke-trace","correlationId":"e2-smoke-correlation","causationId":null,"payload":{"orderId":201,"userId":101,"voucherId":1,"status":1,"version":1,"createdAt":"2026-07-26T04:35:00Z"}}',
    '43777935851c0084f6cdf0f78b045182cc0d6191d519eb1910ef340a9097eca9',
    'READY'
) ON DUPLICATE KEY UPDATE
    status='READY',
    attempts=0,
    next_attempt_at=CURRENT_TIMESTAMP(3),
    published_at=NULL,
    last_error=NULL;
