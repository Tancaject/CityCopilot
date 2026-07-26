SELECT event_id, status, attempts, COALESCE(last_error, '')
FROM outbox_event
WHERE event_id='00000000-0000-0000-0000-000000000201';

SELECT consumer_group, event_id, status, payload_hash
FROM processed_event
WHERE event_id='00000000-0000-0000-0000-000000000201';
