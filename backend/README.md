# Backend Modules

`backend/` contains all Java Maven modules for CityCopilot.

| Module | Role |
|---|---|
| `core` | Business API and persistence: controllers, services, entities, mappers, MySQL, Redis, permissions, audit logs, GEO, seckill. |
| `agent` | Main backend application for the frontend: Agent orchestration, tool gateway, SSE chat, traces, Ops Agent, Actuator. |
| `mcp-server` | MCP-style HTTP tool adapter that reuses the same Agent tool registry and gateway. |
| `eval` | YAML-driven evaluation runner and Markdown report generation. |

`core` only keeps backend resources such as `application.yaml`, mapper XML, and Lua scripts. The historical bundled Nginx/static HTML frontend has been removed; use the root `frontend/` app for UI work.

Dependency direction:

```text
mcp-server -> agent -> core
eval       -> agent -> core
```

Run the main backend from the repository root:

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/agent spring-boot:run
```
