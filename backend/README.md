# Backend Modules

`backend/` contains all Java Maven modules for CityCopilot.

The [architecture acceptance map](../docs/architecture/architecture-acceptance.md) links the prioritized design scope to its invariants and evidence requirements.

| Module | Role |
|---|---|
| `contracts` | Stable Tool, identity, request/response, and error contracts with no business implementation dependency. |
| `http-support` | Shared request deadlines, bounded HTTP concurrency, and cancellable full-response transport; see [request budgets](../docs/architecture/request-budgets.md). |
| `search` | Optional shop-search port and rebuildable Elasticsearch projection; it owns no business facts. |
| `core` | Core/Place facade: identity, community, shops, permissions, audit, GEO, public business APIs, and the internal Tool API. |
| `commerce-service` | Commerce data owner: vouchers, orders, Redis + Lua admission, Reservation, Outbox, DLQ, and inventory consistency. |
| `agent` | Independent Agent service: orchestration, remote Tool gateway, SSE chat, traces, Ops Agent, and an isolated Redis DB. |
| `mcp-server` | Stateless MCP/HTTP adapter that invokes Core through the internal Tool API. |
| `eval` | YAML-driven evaluation runner and Markdown report generation. |

Use the root `frontend/` app for UI work. Deployment examples live under `deploy/nginx/`.

Dependency ownership and persisted JSON compatibility follow [ADR 0011](../docs/adr/0011-supported-dependencies-and-wire-compatibility.md); exact versions live in the Maven POMs and Compose manifest.

Query/index ownership and bounded background scans follow the [SQL access-path policy](../docs/operations/sql-access-paths.md).

Service and durable queue alerts follow the [queue operations runbook](../docs/operations/queue-alerts.md), including role-aware scrape targets and offline rule validation.

Quote cleanup, published Outbox and closed-case Ops evidence payload archival, and retained recovery identities follow the [data lifecycle policy](../docs/operations/data-lifecycle.md).

Service credential overlap, revocation and booking signing-key prerequisites follow the [credential rotation runbook](../docs/operations/service-credential-rotation.md).

Sealed backend artifacts, migration windows and recovery evidence follow the [release and recovery runbook](../docs/operations/release-and-recovery.md).

To start local infrastructure and apply database migrations, run from the repository root:

```powershell
.\tools\citycopilot.ps1 start
```

The command starts infrastructure and runs each database's migration job; run the Java services from the IDE or Maven after migration succeeds. Existing databases and optional development samples follow the [database migration runbook](../docs/database-migrations.md); existing Core notes also require the [community facts cutover](../docs/community-facts.md). Core and Commerce use separately scoped keys in Redis DB 0, Commerce authenticates with its dedicated ACL user, Agent uses DB 1, and Agent calls Core over the versioned internal HTTP API. A separate WSL Redis may keep running on host port 6379 but is not used by the Compose stack.

Dependency direction:

```text
core             -> search
core             -> contracts
core, commerce-service, agent, mcp-server -> http-support
search           -> Elasticsearch Java Client
commerce-service -> contracts
agent            -> contracts
mcp-server       -> contracts
eval             -> agent -> contracts
```

Set each IDE run configuration's working directory to the repository root so the services load `.env`. Start `CityCopilotCommerceApplication`, `CityCopilotCoreApplication`, `CityCopilotAgentApplication`, then `CityCopilotMcpServerApplication`.

Alternatively, with Maven on `PATH`, package the four services and their dependencies from the repository root:

```powershell
mvn -pl backend/commerce-service,backend/core,backend/agent,backend/mcp-server -am package -Dmaven.test.skip=true
```

Then run each command in a separate terminal at the repository root. The packaging command skips tests; it is not acceptance evidence.

```powershell
java -jar backend/commerce-service/target/commerce-service-1.0-SNAPSHOT-exec.jar
java -jar backend/core/target/core-1.0-SNAPSHOT-exec.jar
java -jar backend/agent/target/agent-1.0-SNAPSHOT-exec.jar
java -jar backend/mcp-server/target/mcp-server-1.0-SNAPSHOT-exec.jar
```

Core defaults to `8081`, Agent to `8082`, Commerce to `8083`, and MCP to `8090`. Commerce exclusively owns voucher/order writes; Core fails closed when Commerce is unavailable and does not fall back to its legacy transaction tables. Running them on one computer provides process isolation and independent restart, not host-level high availability.

The default Core schema excludes the retired voucher/order, seckill Reservation, and Outbox tables. The corresponding Core writer, persistence classes, Redis worker/Lua, and migration fallback assets have been removed; voucher and order APIs always fail closed through the Commerce port when Commerce is unavailable.
