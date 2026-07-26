# Backend Modules

`backend/` contains all Java Maven modules for CityCopilot.

| Module | Role |
|---|---|
| `contracts` | Stable Tool, identity, request/response, and error contracts with no business implementation dependency. |
| `core` | Core service and data owner: business APIs, MySQL, business Redis, permissions, authoritative audit, GEO, seckill, and the internal Tool API. |
| `agent` | Independent Agent service: orchestration, remote Tool gateway, SSE chat, traces, Ops Agent, and an isolated Redis DB. |
| `mcp-server` | Stateless MCP/HTTP adapter that invokes Core through the internal Tool API. |
| `eval` | YAML-driven evaluation runner and Markdown report generation. |

Use the root `frontend/` app for UI work. Deployment examples live under `deploy/nginx/`.

For the complete local stack, run the root one-click command instead of opening several terminals:

```powershell
.\tools\citycopilot.ps1 start
```

The Compose stack builds separate executable images for Core, Agent, and MCP. MySQL and the project-specific Docker Redis are reachable through the Compose network; Core uses Redis DB 0, Agent uses DB 1, and Agent calls Core over the versioned internal HTTP API. A separate WSL Redis may keep running on host port 6379 but is not used by the Compose stack.

Dependency direction:

```text
core       -> contracts
agent      -> contracts
mcp-server -> contracts
eval       -> agent -> contracts
```

Set the same internal service token for both processes, then run Core and Agent in separate terminals:

```powershell
$env:CITYCOPILOT_INTERNAL_SERVICE_TOKEN="replace-with-a-long-random-secret"
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/core -am spring-boot:run
```

```powershell
$env:CITYCOPILOT_INTERNAL_SERVICE_TOKEN="replace-with-the-same-secret"
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/agent -am spring-boot:run
```

Core defaults to `8081`, Agent to `8082`, and MCP to `8090`. Only Core owns business data. Running them on one computer provides process isolation and independent restart, not host-level high availability.
