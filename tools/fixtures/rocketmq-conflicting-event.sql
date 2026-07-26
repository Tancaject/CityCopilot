INSERT INTO outbox_event(
    event_id, event_type, schema_version, aggregate_type, aggregate_id,
    aggregate_version, source_service, trace_id, correlation_id, causation_id,
    topic, tag, payload_json, payload_hash, status
) VALUES (
    '00000000-0000-0000-0000-000000000202',
    'OrderCreated',
    1,
    'coupon_order',
    'e2-smoke-order',
    2,
    'core-service',
    'e2-smoke-trace-conflict',
    'e2-smoke-correlation',
    '00000000-0000-0000-0000-000000000201',
    'citycopilot-commerce-events-v1',
    'OrderCreated',
    '{"eventId":"00000000-0000-0000-0000-000000000201","eventType":"OrderCreated","schemaVersion":1,"aggregateType":"coupon_order","aggregateId":"e2-smoke-order","aggregateVersion":2,"sourceService":"core-service","occurredAt":"2026-07-26T04:36:00Z","traceId":"e2-smoke-trace-conflict","correlationId":"e2-smoke-correlation","causationId":"00000000-0000-0000-0000-000000000201","payload":{"orderId":201,"userId":101,"voucherId":1,"status":2,"version":2,"createdAt":"2026-07-26T04:35:00Z"}}',
    '8ab6bbeb1eee18e0fb8644c3585bf29f8c6300a1208318191915c601696bdf93',
    'READY'
) ON DUPLICATE KEY UPDATE
    status='READY',
    attempts=0,
    next_attempt_at=CURRENT_TIMESTAMP(3),
    published_at=NULL,
    last_error=NULL;
