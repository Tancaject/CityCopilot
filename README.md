# CityCopilot

CityCopilot 是一个面向本地生活场景的 Java AI Agent 项目。它将店铺发现、内容互动、优惠券、订单和秒杀等业务能力封装为统一工具，供对话 Agent、MCP 客户端和评测系统复用。

## 核心能力

- ReAct 与 Plan-Execute-Replan 编排，支持 SSE 流式响应、工具调用和会话记忆
- 本地店铺、位置与高德 POI 发现，可选 Elasticsearch 检索投影并支持故障回退
- 统一 Tool 契约和 Core 权威执行入口，复用于 Agent、MCP 与运营任务
- 高风险操作审批、幂等执行、审计、Trace 和重启恢复
- 独立 Commerce 服务，使用 Redis + Lua、Redis Stream、Outbox 和 RocketMQ 保障交易链路
- Prometheus/Grafana 可观测性与 YAML 驱动的离线评测

## 项目结构

| 模块 | 职责 |
|---|---|
| `backend/contracts` | Tool、身份与调用协议 |
| `backend/search` | 可选 Elasticsearch 店铺搜索投影 |
| `backend/core` | 用户、社区、店铺、权限、审计和业务 API |
| `backend/commerce-service` | 优惠券、订单、秒杀、对账和事件 |
| `backend/agent` | Agent 编排、记忆、审批、SSE 和 Trace |
| `backend/mcp-server` | 无状态 MCP 适配层 |
| `backend/eval` | Agent 离线评测 |
| `frontend` | Vue 3 用户端与管理端 |

Core、Commerce、Agent 和 MCP 以独立进程运行；Core 是业务权限与 Tool 执行入口，Commerce 独占交易写入。

## 技术栈

Java 17、Spring Boot 3、Spring AI、MyBatis-Plus、MySQL、Redis、Lua、RocketMQ、Elasticsearch（可选）、Vue 3、TypeScript、Prometheus 和 Grafana。

## 快速开始

准备 JDK 17、Node.js 22+ 和 Docker Desktop，然后在 PowerShell 中启动基础设施：

```powershell
.\tools\citycopilot.ps1 start
```

该命令会创建本地 `.env`，并启动项目所需的 MySQL、Redis 和 RocketMQ。随后从 IDE 依次启动 Commerce、Core、Agent 和 MCP；详细命令与端口见 [后端模块说明](backend/README.md)。

默认配置关闭外部 AI，并允许高德 Key 留空，因此首次启动不需要第三方凭据。服务启动后可检查主要入口：

```powershell
Invoke-RestMethod http://127.0.0.1:8081/actuator/health
Invoke-RestMethod http://127.0.0.1:8082/agent/ready
Invoke-RestMethod http://127.0.0.1:8090/mcp/tools/list
```

启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

前端默认地址为 `http://localhost:5173`。基础设施状态与排障方式见 [Docker Compose 指南](docs/docker-compose.md)。
