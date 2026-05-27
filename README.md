# CityCopilot

CityCopilot 是一个本地生活 AI Agent 项目。项目把店铺、优惠券、订单、秒杀、Redis GEO、审计、运维等能力统一注册为 `@AgentTool`，再复用于对话 Agent、MCP 风格工具调用和评测框架。

## 项目结构

| 模块 | 作用 |
|---|---|
| `backend/core` | CityCopilot 核心业务：MySQL、Redis、Lua 秒杀、Redis GEO、权限、审计日志 |
| `backend/agent` | Agent 主后端服务：Tool Registry、ToolGateway、ReAct、Plan-Execute-Replan、SSE `/agent/chat`、Trace、Ops Agent、Actuator |
| `backend/mcp-server` | MCP 工具层：Spring AI MCP tool callbacks + HTTP-compatible `tools/list`、`tools/call`，复用同一套 Registry/Gateway，并按登录用户权限过滤工具 |
| `backend/eval` | YAML 用例、评测 runner、Markdown 报告 |
| `frontend` | Vue 3 + TypeScript 前端，包含 Agent 流式对话卡片和 Trace 看板 |

后端统一放在 `backend/` 下，依赖方向是 `mcp-server -> agent -> core`，`eval -> agent`。日常启动给前端用的是 `backend/agent`。前端统一在 `frontend/`，`backend/core` 不再打包历史 Nginx 静态页面和二进制文件。更细的后端模块说明见 `backend/README.md`。

## 当前进度

- 已完成：四模块 Maven 架构、CityCopilot 命名迁移、27 个 `@AgentTool` 工具名、ReAct、LLM-first Plan-Execute-Replan、Critic Agent 高风险拦截、`/agent/chat` SSE、Trace 列表/详情、Spring AI MCP tool callbacks、HTTP-compatible `tools/list` / `tools/call`、Resilience4j、Ops Agent live ToolGateway 执行、旧 `/ai/chat` 下线、30 条 eval case、离线 eval 指标报告、Actuator/Prometheus、工具/LLM 自定义 metrics 和 Grafana JSON。
- 仍在打磨：MCP 客户端真实连通验收、带真实 key 的 provider 准确率/延迟/token 报告、完整外部环境验收和 demo 视频。
- 当前计划来源：`docs/superpowers/plans/2026-05-24-citycopilot-implementation.md` 的当前状态和剩余任务清单。

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

DashScope 使用 OpenAI-compatible 接口，默认地址在 `backend/core/src/main/resources/application.yaml` 的 `citycopilot.ai.api-url` 中配置。

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
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/agent spring-boot:run
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

前端会通过 Vite 代理访问后端 `/api`。使用前请确认后端 `backend/agent` 模块已经在 `8081` 端口启动。

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
| `POST /agent/ops/tasks/daily-report` | 投递运营日报任务 |
| `POST /agent/ops/poll-once` | 手动轮询并处理 1 条运营任务 |
| `GET /agent/ops/results?limit=20` | 查看最近运营 Agent 执行结果 |
| `GET /mcp/tools/list` | 查看当前用户可见的已注册工具 |
| `POST /mcp/tools/call` | 调用工具，需要登录态和对应后端权限 |
| `GET /actuator/prometheus` | Prometheus 指标 |

MCP 客户端配置和 smoke 命令见 `docs/mcp-client.md`。

## 快速验收

后端启动后可以做这些检查：

```powershell
curl http://localhost:8081/agent/ping
curl http://localhost:8081/mcp/tools/list
```

真实依赖启动后可以直接跑仓库内 smoke 脚本：

```powershell
.\tools\smoke_mcp_http.ps1
.\tools\smoke_ops_agent.ps1
.\tools\smoke_observability.ps1
```

这些脚本分别验证 MCP HTTP 工具列表与只读调用、运营 Agent 投递/处理/结果查询、Prometheus 指标暴露。

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

当前离线 eval 报告：

- `backend/eval/reports/citycopilot-v01-latest.md`

Eval runner 默认使用离线 deterministic 预测器。配置真实模型 key 后，可以切到 OpenAI-compatible provider 模式：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -q -pl backend/eval -am compile exec:java "-Dexec.args=--output backend/eval/reports/citycopilot-v01-latest.md --fail-on-gate"
```

真实模型服务商评测：

```powershell
$env:CITYCOPILOT_AI_API_KEY="你的 DashScope 或兼容 OpenAI API Key"
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -q -pl backend/eval -am compile exec:java "-Dexec.args=--provider openai-compatible --model qwen-plus --output backend/eval/reports/citycopilot-v01-live.md --fail-on-gate"
```

如果 key 放在其它环境变量里，使用 `--api-key-env 变量名`。真实报告和离线报告要分开保存，避免把离线启发式指标当作真实 LLM 指标。

| 指标 | 值 | 说明 |
|---|---:|---|
| 评测用例数 | 30 | `citycopilot-v01.yaml` |
| 模式准确率 | 100% | Router 模式匹配期望的 `REACT` / `PLAN_EXECUTE` |
| 工具调用准确率 | 100% | 期望工具包含于预测工具 |
| 关键词准确率 | 100% | 命中必含关键词且避开禁用关键词 |
| 平均评测延迟 | 0 ms | 离线 deterministic eval runner，不代表真实 LLM 延迟 |
| 平均估算 token | 27 | 启发式输入 + 输出 token 估算 |
| 失败原因分布 | none | 当前离线报告全部通过；真实 provider 报告会按 mode/tool/keyword 失败分类 |
| 已注册工具数 | 27 | 主 Java 源码中的唯一 `@AgentTool` 名称 |

## 常见问题

如果命令行提示找不到 `mvn`，使用仓库内 Maven：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -v
```

如果后端启动时数据库连接失败，检查 `CITYCOPILOT_DB_URL`、账号密码以及 `citycopilot.sql` 是否已经导入。

如果 Redis 连接失败，检查 `CITYCOPILOT_REDIS_HOST`、`CITYCOPILOT_REDIS_PORT` 和 Redis 服务状态。秒杀链路依赖 Redis、Lua 脚本和 Redisson，测试该链路前必须保证 Redis 可用。

如果 Agent 没有真实大模型回复，检查 `DASHSCOPE_API_KEY`。未配置时项目会保留 fallback，方便本地演示基础链路。
