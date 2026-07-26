# CityCopilot

CityCopilot 是一个面向本地生活场景的 AI Agent 项目，将店铺发现、内容互动、优惠券、订单和秒杀等业务能力统一封装为工具，供对话 Agent、MCP 调用和评测系统复用。

## 核心能力

- 本地店铺与高德周边 POI 混合发现，支持位置选择、距离排序和服务降级
- ReAct 与 Plan-Execute-Replan Agent，支持 SSE 流式响应、工具调用和高风险操作拦截
- 持久化 Ops Agent：Case、证据、计划、人工审批、幂等执行、独立复核和重启恢复
- 独立 Commerce 微服务：订单快照、Redis + Lua 秒杀、Reservation、Outbox/Inbox、DLQ 与库存对账
- RocketMQ 跨服务事件链路，Redis Stream 保留为秒杀准入后的本地工作队列
- 统一 Tool 契约与 Core 权威执行入口，同时服务 Agent、MCP 与运营任务
- Trace、审计日志、Prometheus 指标、Grafana 看板和离线评测

## 项目结构

| 目录 | 说明 |
|---|---|
| `backend/contracts` | 无业务实现依赖的 Tool、身份和调用协议 |
| `backend/core` | Core/Place 门面：身份、社区、店铺、权限、审计和内部 Tool API |
| `backend/commerce-service` | 交易域：独立 MySQL/Redis ACL、券、订单、秒杀、对账和事件 |
| `backend/agent` | Consumer/Ops Agent：编排、Case 状态机、审批、复核、SSE 与 Trace |
| `backend/mcp-server` | 无状态 MCP 适配层，通过 HTTP 调用 Core |
| `backend/eval` | YAML 驱动的 Agent 评测模块 |
| `frontend` | Vue 3 + TypeScript 用户端与管理端 |
| `deploy` | Nginx、Prometheus 和 Grafana 示例配置 |

后端依赖方向：`core/commerce-service/agent/mcp-server -> contracts`，`eval -> agent -> contracts`。Core、Commerce、Agent 和 MCP 是独立进程；Compose 结果证明单机集成与故障隔离，不等于多机高可用。

## 技术栈

- Java 17、Spring Boot 3、Spring AI、MyBatis-Plus
- MySQL、Redis ACL、Redisson、Lua、RocketMQ 5.x、Transactional Outbox、Resilience4j
- Vue 3、TypeScript、Vite
- Prometheus、Grafana、Maven、JUnit

## 快速开始

### Docker 一键启动（推荐）

本项目运行时使用 Compose 内独立的 Redis，不连接 WSL Redis。已有 `.env` 时直接执行：

```powershell
docker compose up -d --build
```

首次执行需要下载基础镜像并完成 Maven/npm 构建，通常需要数分钟。启动完成后访问 `http://127.0.0.1:8080`。

新环境可先让辅助脚本生成未提交到 Git 的随机密码和内部服务令牌：

```powershell
.\tools\citycopilot.ps1 start
```

常用 Docker 命令：

```powershell
docker compose ps
docker compose logs --tail 200 core
docker compose restart
docker compose down --remove-orphans
```

普通 `down` 会保留 MySQL、Docker Redis 和上传文件。只有下面的显式命令会永久删除 Docker 数据卷；WSL Redis 不受影响：

```powershell
.\tools\citycopilot.ps1 reset -Force
```

详细边界与排障方式见 [Docker Compose 启动指南](docs/docker-compose.md)。

### 手动开发环境

#### 1. 准备环境

- JDK 17
- MySQL 8.x
- Redis 6.x 或 7.x
- Node.js 22+

初始化数据库：

```powershell
cmd /c "mysql -uroot -p < citycopilot-v2.sql"
```

按需覆盖本地配置：

```powershell
$env:CITYCOPILOT_DB_USERNAME="root"
$env:CITYCOPILOT_DB_PASSWORD="你的数据库密码"
$env:CITYCOPILOT_REDIS_HOST="127.0.0.1"
$env:CITYCOPILOT_AI_API_KEY="你的 DashScope 或 OpenAI-compatible API Key"
$env:CITYCOPILOT_AMAP_WEB_SERVICE_KEY="你的高德 Web 服务 Key"
$env:CITYCOPILOT_INTERNAL_SERVICE_TOKEN="替换为较长的随机共享密钥"
```

AI 和高德服务 Key 均为可选配置；未配置时，对应能力会使用本地 fallback 或降级结果。

#### 2. 启动后端

手动模式需先启动独立 MySQL/Redis/RocketMQ 依赖和 Commerce，再启动 Core：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/core -am spring-boot:run
```

再在终端 2 使用相同的 `CITYCOPILOT_INTERNAL_SERVICE_TOKEN` 启动 Agent：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/agent -am spring-boot:run
```

Core 默认运行在 `8081`，Agent 默认运行在 `8082`：

```powershell
curl http://localhost:8082/agent/ping
```

#### 3. 启动前端

```powershell
cd frontend
npm ci
npm run dev
```

前端默认访问地址为 `http://localhost:5173`。如需地图选点与地址搜索，可额外配置：

```powershell
$env:VITE_AMAP_JS_KEY="你的高德 JS API Key"
$env:VITE_AMAP_SECURITY_CODE="你的高德安全密钥"
```

## 验证

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd clean verify
cd frontend
npm run build
```

迁移、分布式链路和演示：

```powershell
.\tools\migrations\verify-commerce-cutover.ps1
.\tools\migrations\commerce-cutover-gate.ps1 -Action Preflight
.\tools\golden-demo.ps1
```

## 更多文档

- [后端模块说明](backend/README.md)
- [MCP 客户端接入](docs/mcp-client.md)
- [Commerce 迁移与回滚](docs/architecture/commerce-migration-and-cutover.md)
- [Commerce mutation coverage](docs/architecture/commerce-mutation-coverage.md)
- [分布式 Agent 演进计划](docs/architecture/citycopilot-distributed-agent-evolution-plan.md)
- [实现与设计文档](docs/superpowers/)
