# E2 Reliable Seckill and Messaging Evidence

Date: 2026-07-26

## Implemented boundaries

- Redis Stream workers use unique consumer names and claim stale pending messages.
- Order processing returns structured outcomes; only terminally understood outcomes are acknowledged.
- MySQL Reservation plus Redis hot mirrors use idempotent confirm/release Lua and scheduled crash recovery.
- Order, Reservation transition, and Outbox append share the business transaction.
- Outbox claims use token, lease, and fencing; Inbox enforces same-ID/same-hash deduplication and same-ID/different-hash quarantine.
- RocketMQ server is pinned to 5.5.0 and Java gRPC SDK to 5.2.0.
- Single-node Compose includes NameServer, Broker, Proxy, real topics, and an optional Dashboard profile. It is an integration topology, not HA.

## Verification

- `mvn clean verify`: Core 30 tests (3 environment skips), Agent 46 tests, MCP 6 tests, Eval 3 tests; no failures.
- Compose concurrent seckill smoke: 10 requests, 10 distinct orders, 10 `ORDER_CREATED` Reservations, 10 published Outbox events, 10 processed Inbox rows, DB stock 0, Redis stock 0, pending 0.
- Confirm and release operations were repeated ten times in integration coverage without duplicate stock effects.
- The same event was republished without a second business effect.
- The same event ID with a different payload entered `QUARANTINED`.
- Exact Outbox payload bytes and Inbox hash matched after moving payload storage to `LONGTEXT` with JSON validation.

The detailed concurrent run is recorded in `2026-07-26-e2-seckill-smoke.json`.

## Validation boundary

Docker-backed Testcontainers tests require access to the local Docker daemon. Where the sandbox did not expose that socket, equivalent real-container Compose checks were run and are reported explicitly. Single-node RocketMQ results do not establish broker HA.
