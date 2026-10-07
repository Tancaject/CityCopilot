# Resume Bullets - CityCopilot

**Project: CityCopilot - High-Concurrency Local-Life Transaction Backend**  
Tech: Java 17, Spring Boot, MyBatis-Plus, MySQL, Redis, Lua, Redis Stream, RocketMQ, Prometheus, Vue 3

- Split the transaction domain into an independent Commerce service that exclusively owns voucher, order, and booking writes; Core handles identity, permissions, and public APIs, and fails closed instead of falling back to local writes when Commerce is unavailable.
- Built seckill admission as one Redis Lua script that atomically checks the campaign window, stock, one-order-per-user, and the caller's idempotency key, then reserves stock and enqueues the order command; a MySQL admission ledger records every admission so an unknown outcome can be retried with the same key.
- Moved order persistence off the request path with Redis Stream consumer groups; workers commit order, recovery state, and Outbox in one MySQL transaction, pending entries are reclaimed after a crash, and orders that exhaust retries land in a dead-letter table that admins can replay idempotently.
- Delivered cross-service events with a transactional Outbox relay (claim tokens and fencing) to RocketMQ and an Inbox on the consumer side for deduplication.
- Added scheduled MySQL/Redis stock reconciliation that fences new admissions on drift and lets an admin repair stock only with a fresh evidence hash, with every repair and replay written to the audit log.
- Modeled venue and restaurant booking with conditional MySQL updates, a consistent lock order, and idempotent commands for capacity holds, atomic reschedules, expiry release, and check-in.
- Kept shop GEO as a versioned Redis projection fed by a same-transaction Outbox with fenced rebuilds, so stale writes cannot overwrite newer data and nearby search falls back to MySQL when Redis is unavailable.

Measured throughput, latency, workload sizes and fault-recovery results have one source: [E6 evidence](evidence/2026-07-26-e6-performance-and-recovery.md). Use the stated workload and environment alongside any number copied into a resume.
