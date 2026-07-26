# E3 Ops Agent Closed-Loop Evidence

Date: 2026-07-26

## Scenario

1. Set voucher 100 database stock to 10 and Redis stock mirror to 7.
2. Two consecutive reconciliation passes produced one `StockDiscrepancyDetected` event.
3. RocketMQ delivered the event to the Agent Inbox.
4. The Agent created one Case and collected MySQL, Redis, Reservation, order, and Outbox evidence.
5. Because no real LLM key was configured, the Case entered `MANUAL_REVIEW`; no synthetic plan was created.
6. An expired evidence attempt was rejected. This exposed and fixed a JDBC/database timezone bug by making TTL, lease, retry, and approval comparisons use explicit instants.
7. A new evidence-bound, single-step repair plan was created.
8. A decision carrying a wrong plan hash was rejected.
9. The correct one-time approval was consumed, the executor repaired only `coupon:stock:100`, and the independent reader identity recollected evidence.
10. The Case reached `SUCCEEDED` only after Redis stock became 10 and the fresh verifier returned `verified=true`.

## Observed invariants

- Final Case: `SUCCEEDED`.
- Executions: one `repair_seckill_stock`, status `SUCCEEDED`.
- Final Redis stock: 10.
- Fresh verification: `consistent=true`, `conflicts=[]`.
- Replaying the exact event kept `case_count=1`.
- Reusing the approval was rejected.
- Reader and executor have different credentials, thread pools, and permissions.
- Agent state is stored in a dedicated MySQL schema.

## Automated checks

- Agent unit tests: 48 passed, including approval-policy and state-transition tests.
- Core tests: 27 passed plus 3 explicit environment skips.
- Frontend type check and production build: 1,863 modules transformed successfully.

## Validation boundary

The structured LLM adapter is implemented, but the live provider path remains `not_run` because `CITYCOPILOT_AI_ENABLED=false` and no provider credential was supplied. The verified fallback is manual diagnosis, not a fabricated LLM result.
