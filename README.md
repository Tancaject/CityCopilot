# CityCopilot

CityCopilot 是一个面向本地生活场景的 Java AI Agent 项目。它将店铺发现、内容互动、优惠券、订单和秒杀等业务能力封装为统一工具，供对话 Agent、MCP 客户端和评测系统复用。

## 核心能力

- ReAct 与 Plan-Execute-Replan 编排，支持 SSE 流式响应、工具调用和会话记忆
- 本地店铺、位置与高德 POI 发现，可选 Elasticsearch 检索投影并支持故障回退
- 统一 Tool 契约和 Core 权威执行入口，复用于 Agent、MCP 与运营任务
- 高风险操作审批、幂等执行、审计、Trace 和重启恢复
- 独立 Commerce 服务，使用 Redis + Lua、Redis Stream、Outbox 和 RocketMQ 保障交易链路
- 运动场馆与餐厅预约，支持优惠报价、名额暂留、原子改期、到期释放与到店核销
- Prometheus/Grafana 可观测性与 YAML 驱动的离线评测

## 项目结构

| 模块 | 职责 |
|---|---|
| `backend/contracts` | Tool、身份、业务 DTO 与事件协议 |
| `backend/http-support` | 共享请求时限、HTTP 并发限制与可取消传输 |
| `backend/search` | 可选 Elasticsearch 店铺搜索投影 |
| `backend/core` | 用户、社区、店铺、权限、审计和业务 API |
| `backend/commerce-service` | 优惠券、订单、秒杀、预约、报价、对账和事件 |
| `backend/agent` | Agent 编排、记忆、审批、SSE 和 Trace |
| `backend/mcp-server` | 无状态 MCP 适配层 |
| `backend/eval` | Agent 离线评测 |
| `frontend` | Vue 3 用户端与管理端 |

Core、Commerce、Agent 和 MCP 以独立进程运行；Core 是业务权限与 Tool 执行入口，Commerce 独占交易写入。

## 技术栈

Java、Spring Boot、Spring AI、MyBatis-Plus、MySQL、Redis、Lua、RocketMQ、Elasticsearch（可选）、Vue、TypeScript、Prometheus 和 Grafana。依赖版本以 [Maven 配置](pom.xml)、[前端锁文件](frontend/package-lock.json)和 [Compose 配置](compose.yaml)为准。

## 快速开始

准备 JDK 17、Node.js 22.12+ 和 Docker Desktop，然后在仓库根目录的 PowerShell 中启动基础设施：

```powershell
.\tools\citycopilot.ps1 start
```

该命令会创建本地 `.env`，启动项目所需的 MySQL、Redis 和 RocketMQ，并完成三个数据库的迁移。已有数据库首次接管和可选样例数据导入见 [数据库迁移运行手册](docs/database-migrations.md)。迁移成功后从 IDE 依次启动 Commerce、Core、Agent 和 MCP；详细命令与端口见 [后端模块说明](backend/README.md)。

已有笔记的 Core 数据库还须完成[社交事实切换](docs/community-facts.md)，将旧 Redis 点赞关系核对并导入 MySQL 后再启动服务。

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

预约入口位于店铺详情，用户从 `/bookings` 查看安排，商家从 `/admin/bookings` 管理排期、价目与核销。两条完整操作路径见[预约用户指南](docs/architecture/booking-user-guide.md)，已验证范围和待验收项见[交付证据](docs/evidence/booking-delivery-evidence.md)。
