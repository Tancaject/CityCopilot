# Backend Modules

`backend/` contains all Java Maven modules for CityCopilot.

| Module | Role |
|---|---|
| `contracts` | Stable Tool, identity, request/response, and error contracts with no business implementation dependency. |
| `core` | Core/Place facade: identity, community, shops, permissions, audit, GEO, public business APIs, and the internal Tool API. |
| `commerce-service` | Commerce data owner: vouchers, orders, Redis + Lua admission, Reservation, Outbox, DLQ, and inventory consistency. |
| `agent` | Independent Agent service: orchestration, remote Tool gateway, SSE chat, traces, Ops Agent, and an isolated Redis DB. |
| `mcp-server` | Stateless MCP/HTTP adapter that invokes Core through the internal Tool API. |
| `eval` | YAML-driven evaluation runner and Markdown report generation. |

Use the root `frontend/` app for UI work. Deployment examples live under `deploy/nginx/`.

For the complete local stack, run the root one-click command instead of opening several terminals:

```powershell
.\tools\citycopilot.ps1 start
```

The command starts infrastructure only; run the Java services from the IDE or Maven. Core and Commerce use separately scoped keys in Redis DB 0, Commerce authenticates with its dedicated ACL user, Agent uses DB 1, and Agent calls Core over the versioned internal HTTP API. A separate WSL Redis may keep running on host port 6379 but is not used by the Compose stack.

Dependency direction:

```text
core             -> contracts
commerce-service -> contracts
agent            -> contracts
mcp-server       -> contracts
eval             -> agent -> contracts
```

Load the root `.env`, then run Commerce before Core, followed by Agent and MCP:

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/commerce-service -am spring-boot:run
```

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/core -am spring-boot:run
```

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/agent -am spring-boot:run
```

Core defaults to `8081`, Agent to `8082`, Commerce to `8083`, and MCP to `8090`. Commerce exclusively owns voucher/order writes; Core fails closed when Commerce is unavailable and does not fall back to its legacy transaction tables. Running them on one computer provides process isolation and independent restart, not host-level high availability.

The default Core schema excludes the retired voucher/order, seckill Reservation, and Outbox tables. The corresponding Core writer, persistence classes, Redis worker/Lua, and migration fallback assets have been removed; voucher and order APIs always fail closed through the Commerce port when Commerce is unavailable.
