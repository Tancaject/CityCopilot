# Resume Bullets - CityCopilot

**Project: CityCopilot - Acting AI Agent for Local Life Services**  
Tech: Java 17, Spring Boot 3.2, MyBatis-Plus, MySQL, Redis, Resilience4j, Micrometer, Prometheus, Vue 3, TypeScript

- Built a modular-monolith AI Agent platform with 4 Maven backend modules (`core`, `agent`, `mcp-server`, `eval`) and a Vue 3 frontend, separating business services, agent orchestration, MCP exposure, and evaluation.
- Designed a Spring bean based Tool Registry with `@AgentTool`, exposing 27 local-life business tools through both Agent orchestration and MCP-style tool surfaces with one registration model.
- Implemented dual orchestration modes: ReAct for simple user intents and LLM-first Plan-Execute-Replan for multi-step flows, including `$stepN.field` reference resolution and retryable-failure replanning.
- Added multi-agent safeguards: Dialog Agent over SSE, Ops Agent structured task processors with live ToolGateway execution, and Critic Agent review before high-risk operations such as order, payment, refund, follow, and batch geo tasks.
- Hardened tool execution with permission checks, parameter validation, standardized tool errors, audit logging, Resilience4j retry/rate-limit/circuit-breaker/timeout controls, and protected trace APIs.
- Implemented eval harness with 30 YAML cases, Markdown reporting, and failure-reason distribution. Latest offline run: 100% mode accuracy, 100% tool-call accuracy, 100% keyword accuracy, 0 ms average eval latency, and 27 estimated tokens per case.
- Added observability through Actuator, Prometheus scrape config, Grafana dashboard JSON, and custom Micrometer metrics for tool and LLM calls.
- Implemented mixed nearby discovery that merges orderable local shops with AMap POI results from the shared consumer location state, preserving source, orderability, and routing hints for UI and Agent flows.
- Captured seckill load-test evidence: 200 concurrent users at about 2700 QPS with P99 105 ms and 0% errors; 5000 users competing for 100 stock completed with zero oversell.

Open before final resume use: run live provider eval with DashScope credentials using the eval provider mode, perform real MySQL/Redis/AMap/DashScope acceptance, capture MCP client smoke evidence, tune live Ops Agent task payloads, and record a final demo.
