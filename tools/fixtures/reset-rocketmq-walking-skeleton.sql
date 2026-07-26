DELETE FROM processed_event
WHERE consumer_group='citycopilot-event-audit-v1'
  AND event_id='00000000-0000-0000-0000-000000000201';

DELETE FROM event_projection_version
WHERE consumer_group='citycopilot-event-audit-v1'
  AND aggregate_type='coupon_order'
  AND aggregate_id='e2-smoke-order';

DELETE FROM outbox_event
WHERE event_id IN (
    '00000000-0000-0000-0000-000000000201',
    '00000000-0000-0000-0000-000000000202'
);
