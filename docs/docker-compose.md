# CityCopilot Docker 基础设施启动指南

## 直接启动

前置条件是 Docker Desktop 已运行，仓库根目录已有 `.env`。执行：

```powershell
docker compose up -d
```

Compose 默认只启动基础设施：

- Core、Agent、Commerce 各自的 MySQL
- 项目专用 Redis，以及一次性的 `redis-init`
- RocketMQ NameServer、Broker、Proxy 和初始化任务

Elasticsearch 位于可选的 `search` profile，不会被默认基础设施脚本启动：

```powershell
docker compose --profile search up -d elasticsearch
```

它仅保存可从 MySQL 重建的店铺搜索投影，数据卷为 `elasticsearch-data`。启用方式和降级边界见 [Elasticsearch 店铺搜索投影](design/elasticsearch-shop-search.md)。

`core`、`commerce`、`agent`、`mcp` 和 `frontend` 位于可选的 `app` profile，日常开发不启用，由 IDE 启动。CI 如需验证完整容器拓扑，显式执行 `docker compose --profile app up -d --build --wait`。

## Redis 边界

Compose 使用独立的 `redis:7-alpine` 容器：

- 容器网络地址：`redis:6379`
- Windows 调试地址：`127.0.0.1:6380`
- Core 使用 DB 0 中的非交易 key
- Commerce 使用 DB 0 的 `commerce:*` key，并通过独立 `commerce` ACL 用户访问
- Agent 使用 DB 1
- 数据保存在 `redis-data` 命名卷

WSL Redis 仍可运行在 `127.0.0.1:6379`，但 Compose 不连接它。两个 Redis 端口不同、数据独立，不会互相覆盖。

## 服务与数据所有权

| 服务 | 职责 | 持久化 |
|---|---|---|
| `mysql` | Core 业务数据库 | `mysql-data` |
| `agent-mysql` | Agent Trace、Ops 与长期偏好数据库 | `agent-mysql-data` |
| `commerce-mysql` | Commerce 订单与交易数据库 | `commerce-mysql-data` |
| `redis` | Core DB 0、Commerce ACL/keyspace、Agent DB 1、Redis Stream | `redis-data` |
| `redis-init` | 幂等创建并验证 Commerce Redis ACL 用户，完成后退出 | 无 |
| `rocketmq-namesrv` | RocketMQ 名称服务 | `rocketmq-namesrv-logs` |
| `rocketmq-broker` | RocketMQ Broker | `rocketmq-broker-store`、`rocketmq-broker-logs` |
| `rocketmq-proxy` | 本地 Java 服务访问 RocketMQ 的 gRPC Proxy | 无 |
| `elasticsearch` | 可选店铺全文检索投影，不是业务事实源 | `elasticsearch-data` |

MySQL 仅在空数据卷首次创建时导入对应初始化 SQL。`.\tools\citycopilot.ps1 start` 和 `restart` 会在健康检查后执行可审计的增量迁移；当前会预检并补齐店铺 GCJ-02 坐标列与约束，异常数据存在时会停止而不是强行修改。

## 端口

- Core MySQL：`127.0.0.1:3307`
- Agent MySQL：`127.0.0.1:3308`
- Commerce MySQL：`127.0.0.1:3309`
- Docker Redis：`127.0.0.1:6380`
- RocketMQ NameServer：`127.0.0.1:9876`
- RocketMQ Broker：`127.0.0.1:10911`
- RocketMQ Proxy：`127.0.0.1:18080` / `127.0.0.1:18081`
- Elasticsearch（可选）：`127.0.0.1:9200`

所有 Compose 端口默认只绑定本机。

Java 服务的 Working directory 设为仓库根目录后会自动加载 `.env`，并连接上述宿主机端口。源码默认值已对齐 3307/3308/3309、6380 和 18081。Vue 开发服务器运行在 `5173`，并把 API 代理到本地 Core `8081` 与 Agent `8082`。

本地 RocketMQ 默认启用：Commerce Outbox Relay 与跨服务 Consumer 连接 Proxy，Redis Stream 继续负责秒杀准入后的本地订单消费。只有在明确诊断 MQ 故障时才临时设置 `CITYCOPILOT_ROCKETMQ_ENABLED=false`，并保留所有未发布 Outbox。

## 常用命令

```powershell
# 查看状态
.\tools\citycopilot.ps1 status

# 查看日志
.\tools\citycopilot.ps1 logs -Service redis

# 重启基础设施并重新创建/校验 Commerce Redis ACL
.\tools\citycopilot.ps1 restart

# 停止并保留数据卷
.\tools\citycopilot.ps1 stop

# 删除 MySQL、Docker Redis、Elasticsearch 搜索投影和上传文件卷
.\tools\citycopilot.ps1 reset -Force
```

不要用裸 `docker compose restart` 代替脚本的 restart；`redis-init` 是一次性任务，脚本会在 Redis 重启后再次执行它。

不要提交 `.env`。正式部署应使用 Docker secrets 或外部密钥管理。

## 排障

- Docker daemon 不可用：启动 Docker Desktop 后重试。
- 本地 Java 服务无法连接 Redis：确认 `redis` 为 `healthy`，IDE Working directory 是仓库根目录，并使用 `127.0.0.1:6380`。
- 本地 Java 服务无法连接 MySQL：确认使用对应的 3307/3308/3309 端口，并加载根 `.env` 中对应账号密码。
- Java 服务提示 8081/8082/8083/8090 被占用但系统查不到进程：这是已移除应用容器遗留的 Docker Desktop loopback 代理，执行 `docker desktop restart` 后再启动。
- Core 当前表不存在：确认使用 `citycopilot-v2.sql`。默认脚本只创建 Core 当前拥有的表；旧交易表和旧迁移入口已从仓库删除。
- 前端地图配置：本地 Vite 读取 `frontend/.env`，不再依赖 Docker 前端镜像。
- 附近店铺无法点击：高德外部地点只用于发现展示，不能下单。页面会标明“外部地点 · 暂不支持下单”；附近没有入驻店铺时，点击“查看可下单店铺”回到本地交易店铺。

## 本地秒杀压测

先启动 Docker 基础设施，并在 IDE 中启动 Commerce（8083）和 Core（8081）。默认本地模式同时启用 Redis Stream 订单消费和 RocketMQ Outbox Relay：

### JMeter GUI 正式性能测试

用 JMeter 打开 `tools/loadtest/CityCopilot-Seckill-Performance-GUI.jmx`，直接点击绿色启动按钮。该计划固定采用“20,000 个唯一用户、20,000 库存、200 并发、5 秒升压”的全成功性能场景，并在正式计时前自动完成：

1. 等待上一轮 Redis Stream 的 pending 和 lag 归零；
2. 删除专用券 `200` 的旧订单、旧买家集合和上一轮测试 token；
3. 重新生成 20,000 个测试登录用户，并将 MySQL/Redis 库存重置为 20,000。

先确认“准备结果（必须为绿色）”成功，再查看 `Aggregate Report（正式结果）`。正式测试默认禁用“失败详情”监听器，避免它占用内存并干扰性能数据；只有排障复现时才手动启用。不要继续使用旧的 `CityCopilot-Seckill.jmx` 作为正式结果，它保留为诊断和非 GUI 自动化的兼容输入。

### 非 GUI 自动化测试

```powershell
.\tools\loadtest\run_jmeter_commerce.ps1 -Users 5000 -Threads 200 -RampSeconds 10
```

脚本会重置专用测试券 `200`，创建一次性测试登录态，运行非 GUI JMeter，并校验请求错误率、每用户一单、无超卖及 MySQL/Redis 库存一致性。结果写入 `tools/loadtest/results/<runId>/`。可用 `-JMeterHome` 指定本机 JMeter 目录。

RocketMQ Relay 默认开启；报告会等待并核对本轮 Outbox 发布结果。若此前在 IDEA Run Configuration 中显式设置过 `CITYCOPILOT_ROCKETMQ_ENABLED=false`，应删除该覆盖或改为 `true`，重启 Commerce 后再执行：

```powershell
.\tools\loadtest\run_jmeter_commerce.ps1 -Users 5000 -Threads 200 -RequireOutboxDrain
```

`-BrokerOutage` 用于“暂停 Proxy 压测后恢复并等待 Outbox 清零”的故障恢复场景，同样要求 Commerce 已启用 RocketMQ Relay。

## 架构边界

Compose 只负责有状态基础设施的隔离与持久化；业务进程由 IDE 管理。Docker Redis 是项目的隔离运行依赖，不是对 Windows 或 WSL Redis 的重新安装。
