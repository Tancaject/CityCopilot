# CityCopilot

CityCopilot 是一个本地生活 AI Agent 项目。项目把店铺、优惠券、订单、秒杀、Redis GEO、审计、运维等能力统一注册为 `@AgentTool`，再复用于对话 Agent、MCP 风格工具调用和评测框架。

## 项目结构

| 模块 | 作用 |
|---|---|
| `core` | CityCopilot 核心业务：MySQL、Redis、Lua 秒杀、Redis GEO、权限、审计日志 |
| `agent` | Agent 主服务：Tool Registry、ToolGateway、ReAct、Plan-Execute、SSE `/agent/chat`、Trace、Ops Agent、Actuator |
| `mcp-server` | MCP 风格 HTTP 工具层：`tools/list`、`tools/call`，复用同一套 Registry/Gateway |
| `eval` | YAML 用例、评测 runner、Markdown 报告 |
| `frontend` | Vue 3 + TypeScript 前端，包含 Agent 流式对话卡片和 Trace 看板 |

依赖方向是 `mcp-server -> agent -> core`，`eval -> agent`。

## 当前进度

- 已完成：四模块 Maven 架构、CityCopilot 命名迁移、27 个 `@AgentTool` 工具名、ReAct、基础 Plan-Execute、`/agent/chat` SSE、Trace 列表/详情、HTTP MCP-style `tools/list` / `tools/call`、Resilience4j、10 条 eval case、Actuator/Prometheus 和 Grafana JSON。
- 仍在打磨：LLM 驱动的 Plan-Execute-Replan、Critic Agent、完整 MCP 协议 transport、30 条 eval 与真实准确率/延迟/token 指标、工具/LLM 自定义 metrics、真实 Ops Agent 处理逻辑、完整外部环境验收和 demo 视频。
- 当前计划来源：`docs/superpowers/plans/2026-05-24-citycopilot-implementation.md` 的 **Current Execution Status**。

## 启动前准备

需要本机有：

- JDK 17
- MySQL 8.x，并创建 `citycopilot` 数据库
- Redis 6.x 或 7.x
- Node.js 18+ 和 npm
- Maven：可以用系统 Maven，也可以用仓库内已准备的 `.tools/apache-maven-3.9.9/bin/mvn.cmd`

初始化数据库：

```powershell
mysql -uroot -p citycopilot < citycopilot.sql
```

如果本机没有 `citycopilot` 数据库，先执行：

```sql
CREATE DATABASE citycopilot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

## 后端配置

后端默认端口是 `8081`。推荐用 `CITYCOPILOT_*` 环境变量覆盖本地配置：

```powershell
$env:CITYCOPILOT_DB_URL="jdbc:mysql://127.0.0.1:3306/citycopilot?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
$env:CITYCOPILOT_DB_USERNAME="root"
$env:CITYCOPILOT_DB_PASSWORD="你的数据库密码"
$env:CITYCOPILOT_REDIS_HOST="127.0.0.1"
$env:CITYCOPILOT_REDIS_PORT="6379"
$env:CITYCOPILOT_REDIS_PASSWORD=""
```

AI 和地图能力是可选增强项。没有配置时，Agent 仍可走本地启发式 fallback；真实地图/大模型效果需要配置下面两个 key：

```powershell
$env:CITYCOPILOT_AMAP_WEB_SERVICE_KEY="你的高德 Web 服务 Key"
$env:DASHSCOPE_API_KEY="你的 DashScope API Key"
```

DashScope 使用 OpenAI-compatible 接口，默认地址在 `core/src/main/resources/application.yaml` 的 `citycopilot.ai.api-url` 中配置。

## 构建和启动后端

先在项目根目录验证整仓构建：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd clean verify
```

如果你已经安装了系统 Maven，也可以直接用：

```powershell
mvn clean verify
```

启动主后端服务：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl agent spring-boot:run
```

启动成功后访问：

```powershell
curl http://localhost:8081/agent/ping
```

预期能返回 Agent 健康信息。

## 启动前端

进入前端目录安装依赖并启动：

```powershell
cd frontend
npm install
npm run dev
```

Vite 会输出本地访问地址，通常是：

```text
http://localhost:5173
```

前端会通过 Vite 代理访问后端 `/api`。使用前请确认后端 `agent` 模块已经在 `8081` 端口启动。

构建前端生产包：

```powershell
cd frontend
npm run build
```

## 常用接口

| 接口 | 说明 |
|---|---|
| `GET /agent/ping` | Agent 健康检查 |
| `POST /agent/chat` | Agent SSE 对话入口 |
| `GET /agent/traces?limit=50` | 最近 Trace 列表，需要 `audit:read` 权限 |
| `GET /agent/traces/{traceId}` | Trace 详情，需要 `audit:read` 权限 |
| `GET /mcp/tools/list` | 查看已注册工具 |
| `POST /mcp/tools/call` | 调用工具，需要登录态和对应后端权限 |
| `GET /actuator/prometheus` | Prometheus 指标 |

## 快速验收

后端启动后可以做这些检查：

```powershell
curl http://localhost:8081/agent/ping
curl http://localhost:8081/mcp/tools/list
```

前端启动后，打开 Vite 地址，登录后在 AI 面板输入：

- `附近有什么火锅`
- `这家店有什么优惠券`
- `我的订单`

正常情况下可以看到流式回复、工具调用卡片和 `traceId`。涉及下单、支付、取消、退款等写操作时，后端会按登录用户权限受控执行或拒绝。

## 评测和可观测性

运行后端测试：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd clean verify
```

运行前端构建检查：

```powershell
cd frontend
npm run build
```

可观测性文件：

- `deploy/prometheus.yml`
- `deploy/grafana-citycopilot-dashboard.json`

## 常见问题

如果命令行提示找不到 `mvn`，使用仓库内 Maven：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -v
```

如果后端启动时数据库连接失败，检查 `CITYCOPILOT_DB_URL`、账号密码以及 `citycopilot.sql` 是否已经导入。

如果 Redis 连接失败，检查 `CITYCOPILOT_REDIS_HOST`、`CITYCOPILOT_REDIS_PORT` 和 Redis 服务状态。秒杀链路依赖 Redis、Lua 脚本和 Redisson，测试该链路前必须保证 Redis 可用。

如果 Agent 没有真实大模型回复，检查 `DASHSCOPE_API_KEY`。未配置时项目会保留 fallback，方便本地演示基础链路。
