# CityCopilot

CityCopilot 是一个面向本地生活场景的 AI Agent 项目，将店铺发现、内容互动、优惠券、订单和秒杀等业务能力统一封装为工具，供对话 Agent、MCP 调用和评测系统复用。

## 核心能力

- 本地店铺与高德周边 POI 混合发现，支持位置选择、距离排序和服务降级
- 可选 Elasticsearch 店铺全文检索投影，支持 shadow 对比、MySQL 回表校验和故障回退
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
| `backend/search` | 可选店铺搜索端口与 Elasticsearch 可重建读模型，不持有业务事实 |
| `backend/core` | Core/Place 门面：身份、社区、店铺、权限、审计和内部 Tool API |
| `backend/commerce-service` | 交易域：独立 MySQL/Redis ACL、券、订单、秒杀、对账和事件 |
| `backend/agent` | Consumer/Ops Agent：编排、Case 状态机、审批、复核、SSE 与 Trace |
| `backend/mcp-server` | 无状态 MCP 适配层，通过 HTTP 调用 Core |
| `backend/eval` | YAML 驱动的 Agent 评测模块 |
| `frontend` | Vue 3 + TypeScript 用户端与管理端 |
| `deploy` | Nginx、Prometheus 和 Grafana 示例配置 |

后端依赖方向：`core -> search`，`core/commerce-service/agent/mcp-server -> contracts`，`eval -> agent -> contracts`；Search 不依赖业务实现模块。Search 是随 Core 部署的库模块；Core、Commerce、Agent 和 MCP 是独立进程。Compose 结果证明单机集成与故障隔离，不等于多机高可用。

## 技术栈

- Java 17、Spring Boot 3、Spring AI、MyBatis-Plus
- MySQL、Redis ACL、Lua、Redis Stream、RocketMQ 5.x、Elasticsearch（可选）、Transactional Outbox、Resilience4j
- Vue 3、TypeScript、Vite
- Prometheus、Grafana、Maven、JUnit

## 快速开始

### Docker 启动基础设施（推荐）

推荐始终通过辅助脚本启动基础设施；它只启动 MySQL、Redis 和 RocketMQ，移除遗留应用容器，并在 Redis 就绪后重新创建/校验 Commerce ACL：

```powershell
.\tools\citycopilot.ps1 start
```

Compose 文件仍定义了可选的完整应用容器，因此不要用无服务名的 `docker compose up -d` 代替上述入口。脚本会提供 Core、Agent、Commerce 各自的 MySQL，项目专用 Redis，以及 RocketMQ NameServer、Broker 和 Proxy；前端、Core、Commerce、Agent、MCP 均由 IDE 或本地命令启动。

新环境可先让辅助脚本生成未提交到 Git 的随机密码和内部服务令牌：

```powershell
.\tools\citycopilot.ps1 start
```

常用 Docker 命令：

```powershell
.\tools\citycopilot.ps1 status
.\tools\citycopilot.ps1 logs -Service redis
.\tools\citycopilot.ps1 restart
.\tools\citycopilot.ps1 stop
```

普通 `down` 会保留 MySQL、Docker Redis、Elasticsearch 搜索投影和上传文件。只有下面的显式命令会永久删除 Docker 数据卷；WSL Redis 不受影响：

```powershell
.\tools\citycopilot.ps1 reset -Force
```

详细边界与排障方式见 [Docker Compose 启动指南](docs/docker-compose.md)。

### 手动开发环境

#### 1. 准备环境

- JDK 17
- Node.js 22+
- 已启动上述 Docker 基础设施

四个 Spring Boot 模块会在工作目录为仓库根目录时自动加载 `.env`，复用 Docker 数据库密码、Redis 密码和内部服务令牌；IDE 无需重复填写环境变量。本地默认连接端口如下：

- Core MySQL：`127.0.0.1:3307`
- Agent MySQL：`127.0.0.1:3308`
- Commerce MySQL：`127.0.0.1:3309`
- Redis：`127.0.0.1:6380`
- RocketMQ Proxy：`127.0.0.1:18081`
- Elasticsearch（可选 search profile）：`127.0.0.1:9200`

AI 和高德服务 Key 均为可选配置；未配置时，对应能力会使用本地 fallback 或降级结果。RocketMQ 默认启用，Commerce Outbox Relay 和跨服务 Consumer 会连接本地 Proxy；秒杀准入后的本地消费仍使用 Redis Stream。仅在明确的 MQ 故障隔离场景中才设置 `CITYCOPILOT_ROCKETMQ_ENABLED=false`，关闭期间不得删除未发布 Outbox。

店铺全文检索默认关闭并保持 `mysql` 模式。需要验证 Elasticsearch 时，先按 [Elasticsearch 店铺搜索投影](docs/design/elasticsearch-shop-search.md) 启动可选 profile，并使用 `shadow` 模式比较结果。

#### 2. 启动后端

建议在 IDE 中依次启动 Commerce、Core、Agent 和 MCP。等价的命令行入口如下，每个服务使用独立终端：

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/commerce-service -am spring-boot:run
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/core -am spring-boot:run
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/agent -am spring-boot:run
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/mcp-server -am spring-boot:run
```

Core、Agent、Commerce、MCP 默认分别运行在 `8081`、`8082`、`8083`、`8090`：

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
# 校验当前 Commerce 表结构与库存完整性
.\tools\migrations\verify-commerce-integrity.ps1

# 校验当前 Outbox、Reservation、DLQ、恢复记录和 Redis Stream 已排空
.\tools\migrations\commerce-runtime-drain-gate.ps1

# 当前 Commerce-only 演示
.\tools\golden-demo.ps1
```

默认 `citycopilot-v2.sql` 只初始化 Core 当前拥有的 Identity、Place、Community 和事件 Inbox/版本表。旧 Core 交易实现、旧表初始化、旧 Redis Stream/Lua 和迁移回退工具已经从仓库物理删除，交易请求只能进入 Commerce。已有 MySQL/Redis 数据卷可能仍保留历史表或 key；代码更新不会自动删除这些数据，清理前必须单独备份并核对。

## 更多文档

- [后端模块说明](backend/README.md)
- [MCP 客户端接入](docs/mcp-client.md)
- [Commerce 所有权与运行门禁](docs/architecture/commerce-migration-and-cutover.md)
- [Commerce mutation coverage](docs/architecture/commerce-mutation-coverage.md)
- [分布式 Agent 演进计划](docs/architecture/citycopilot-distributed-agent-evolution-plan.md)
- [实现与设计文档](docs/superpowers/)
