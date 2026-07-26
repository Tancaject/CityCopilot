# CityCopilot Docker Compose 启动指南

## 直接启动

前置条件是 Docker Desktop 已运行，仓库根目录已有 `.env`。执行：

```powershell
docker compose up -d --build
```

Compose 会启动 MySQL、Docker Redis、Core、Agent、MCP 和前端，并用一次性的 `redis-init` 幂等创建秒杀消费组 `g1`。完成后访问 `http://127.0.0.1:8080`。

首次启动需要下载基础镜像和 Maven/npm 依赖，通常比后续启动慢。后续没有代码变化时可执行：

```powershell
docker compose up -d
```

## Redis 边界

Compose 使用独立的 `redis:7-alpine` 容器：

- 容器网络地址：`redis:6379`
- Windows 调试地址：`127.0.0.1:6380`
- Core 使用 DB 0
- Agent 使用 DB 1
- 数据保存在 `redis-data` 命名卷

WSL Redis 仍可运行在 `127.0.0.1:6379`，但 Compose 不连接它。两个 Redis 端口不同、数据独立，不会互相覆盖。

## 服务与数据所有权

| 服务 | 职责 | 持久化 |
|---|---|---|
| `mysql` | Core 业务数据库 | `mysql-data` |
| `redis` | Core DB 0、Agent DB 1、Redis Stream | `redis-data` |
| `redis-init` | 幂等创建秒杀消费组，完成后退出 | 无 |
| `core` | 业务、权限、审计、内部 Tool API | `uploads-data` 读写 |
| `agent` | LLM、SSE、编排、记忆与 Trace | 使用 Redis DB 1 |
| `mcp` | 无状态 MCP 到 Core 适配器 | 无 |
| `frontend` | Vue 静态站点与 Nginx 统一入口 | `uploads-data` 只读 |

MySQL 仅在空数据卷首次创建时导入 `citycopilot-v2.sql`。修改 SQL 文件不会自动更新已有数据卷。

## 端口

- 前端统一入口：`http://127.0.0.1:8080`
- Core：`http://127.0.0.1:8081`
- Agent：`http://127.0.0.1:8082`
- MCP：`http://127.0.0.1:8090`
- MySQL：`127.0.0.1:3307`
- Docker Redis：`127.0.0.1:6380`
- WSL Redis（项目不使用）：`127.0.0.1:6379`

所有 Compose 端口默认只绑定本机。

## 常用命令

```powershell
# 查看状态
docker compose ps -a

# 查看日志
docker compose logs --tail 200 core

# 重启
docker compose restart

# 停止并保留数据卷
docker compose down --remove-orphans

# 删除 MySQL、Docker Redis 和上传文件卷
.\tools\citycopilot.ps1 reset -Force
```

不要提交 `.env`。正式部署应使用 Docker secrets 或外部密钥管理。

## 排障

- Docker daemon 不可用：启动 Docker Desktop 后重试。
- Core 无法连接 Redis：确认 `redis` 为 `healthy`，且 Core 使用 `redis:6379`，不是 `host.docker.internal`。
- 数据库表不存在：确认使用 `citycopilot-v2.sql`；旧卷需要迁移，或确认无需数据后重置。
- 修改 `VITE_*` 后未生效：重新构建前端镜像。

## 架构边界

Compose 在一台电脑上提供进程隔离、健康依赖、独立重启和统一入口，但不提供主机级高可用。Docker Redis 是项目的隔离运行依赖，不是对 Windows 或 WSL Redis 的重新安装。
