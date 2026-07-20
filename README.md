# CityCopilot

CityCopilot 是一个面向本地生活场景的 AI Agent 项目，将店铺发现、内容互动、优惠券、订单和秒杀等业务能力统一封装为工具，供对话 Agent、MCP 调用和评测系统复用。

## 核心能力

- 本地店铺与高德周边 POI 混合发现，支持位置选择、距离排序和服务降级
- ReAct 与 Plan-Execute-Replan Agent，支持 SSE 流式响应、工具调用和高风险操作拦截
- 店铺、内容、优惠券、订单及 Redis + Lua 秒杀业务链路
- 统一 Tool Registry，同时服务 Agent、MCP 与运营任务
- Trace、审计日志、Prometheus 指标、Grafana 看板和离线评测

## 项目结构

| 目录 | 说明 |
|---|---|
| `backend/core` | 核心业务、MySQL、Redis、权限与审计 |
| `backend/agent` | Agent 编排、工具网关、SSE 对话与 Trace，也是前端使用的主后端 |
| `backend/mcp-server` | 复用 Agent 工具体系的 MCP 适配层 |
| `backend/eval` | YAML 驱动的 Agent 评测模块 |
| `frontend` | Vue 3 + TypeScript 用户端与管理端 |
| `deploy` | Nginx、Prometheus 和 Grafana 示例配置 |

后端模块依赖方向：`mcp-server -> agent -> core`，`eval -> agent -> core`。

## 技术栈

- Java 17、Spring Boot 3、Spring AI、MyBatis-Plus
- MySQL、Redis、Redisson、Lua、Resilience4j
- Vue 3、TypeScript、Vite
- Prometheus、Grafana、Maven、JUnit

## 快速开始

### 1. 准备环境

- JDK 17
- MySQL 8.x
- Redis 6.x 或 7.x
- Node.js 18+

初始化数据库：

```powershell
cmd /c "mysql -uroot -p < citycopilot.sql"
```

按需覆盖本地配置：

```powershell
$env:CITYCOPILOT_DB_USERNAME="root"
$env:CITYCOPILOT_DB_PASSWORD="你的数据库密码"
$env:CITYCOPILOT_REDIS_HOST="127.0.0.1"
$env:CITYCOPILOT_AI_API_KEY="你的 DashScope 或 OpenAI-compatible API Key"
$env:CITYCOPILOT_AMAP_WEB_SERVICE_KEY="你的高德 Web 服务 Key"
```

AI 和高德服务 Key 均为可选配置；未配置时，对应能力会使用本地 fallback 或降级结果。

### 2. 启动后端

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl backend/agent -am spring-boot:run
```

后端默认运行在 `http://localhost:8081`，可通过以下命令检查：

```powershell
curl http://localhost:8081/agent/ping
```

### 3. 启动前端

```powershell
cd frontend
npm install
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

## 更多文档

- [后端模块说明](backend/README.md)
- [MCP 客户端接入](docs/mcp-client.md)
- [实现与设计文档](docs/superpowers/)
