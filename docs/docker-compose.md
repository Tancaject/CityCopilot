# CityCopilot Docker 基础设施启动指南

## 直接启动

前置条件是 Docker Desktop 已运行，仓库根目录已有 `.env`。执行：

```powershell
docker compose up -d
```

Compose 默认启动基础设施及一次性迁移任务：

- Core、Commerce 各自的 MySQL
- Core、Commerce 各自的 Flyway 迁移任务
- Core 缓存 Redis；Commerce 交易 Redis 主节点与一个副本
- 定时 MySQL 备份任务 `backup`
- Kafka 3 节点 KRaft 集群和建 topic 的初始化任务

`core`、`commerce` 和 `frontend` 位于可选的 `app` profile，日常开发不启用，由 IDE 启动。容器里的 Core、Commerce 固定以 `production` profile 运行（ECS JSON 日志、INFO 级别、后台角色由环境变量显式打开），与单机部署形态一致。CI 如需验证完整容器拓扑，显式执行 `docker compose --profile app up -d --build --wait`。

## Redis 边界

Core 与 Commerce 使用两套 Redis，故障和内存压力互不影响：

- `redis`（`127.0.0.1:6380`）：Core 缓存、登录令牌、限流和 GEO 索引。`maxmemory` 默认 192mb，`volatile-lru` 只淘汰带 TTL 的键，数据在 `redis-data`。
- `commerce-redis`（`127.0.0.1:6381`）：Commerce 库存、准入、幂等键和订单 Stream。`maxmemory` 默认 160mb，`noeviction` 满了拒绝写入，不会悄悄丢库存。`commerce` ACL 用户（只能访问 `commerce:*`，禁用危险命令）写在启动参数里，重启后自动恢复。数据在 `commerce-redis-data`。
- `commerce-redis-replica`：`commerce-redis` 的副本。生产配置要求准入写入经 `WAIT` 得到 1 个副本确认，单机部署也真实执行这一步。

两套 Redis 都开启 AOF。Commerce Redis 不单独备份：库存与准入以 MySQL 账本为准，丢失后由启动时的 inventory bootstrap 和一致性修复重建。

WSL Redis 仍可运行在 `127.0.0.1:6379`，但 Compose 不连接它。两个 Redis 端口不同、数据独立，不会互相覆盖。

## 服务与数据所有权

| 服务 | 职责 | 持久化 |
|---|---|---|
| `mysql` | Core 业务数据库 | `mysql-data` |
| `commerce-mysql` | Commerce 订单与交易数据库 | `commerce-mysql-data` |
| `redis` | Core 缓存、令牌、限流、GEO | `redis-data` |
| `commerce-redis` | Commerce 库存、准入、Redis Stream | `commerce-redis-data` |
| `commerce-redis-replica` | Commerce Redis 副本，供 `WAIT` 确认 | `commerce-redis-replica-data` |
| `backup` | 定时 `mysqldump` 两个库，保留 7 天 | `backups-data` |
| `kafka-1`～`kafka-3` | Kafka 节点，每个同时是 broker 和 controller；复制因子 3、`min.insync.replicas=2` | `kafka-1-data`～`kafka-3-data` |
| `kafka-init` | 幂等创建业务主题和对应 `.DLT` 死信主题（6 分区、3 副本），完成后退出 | 无 |
| `kafka-ui` | 可选的 Kafka 管理界面，`--profile mq-dashboard` 时启动 | 无 |

数据库结构由独立迁移任务维护，应用容器等待对应任务成功后启动。日常启动、已有数据卷接管、迁移失败处置和可选开发样例导入统一见 [数据库迁移运行手册](database-migrations.md)。后台 `docker compose up -d` 返回不表示迁移成功，应查看任务退出状态。

## 备份与恢复演练

`backup` 容器每天（`CITYCOPILOT_BACKUP_INTERVAL_SECONDS`，默认 86400）用 `mysqldump --single-transaction` 备份 Core 和 Commerce 两个库到 `backups-data` 卷，保留 `CITYCOPILOT_BACKUP_RETENTION_DAYS`（默认 7）天。备份文件不含库名，恢复时必须显式指定目标库。

恢复演练把最新备份导入临时库，核对表数量和 Flyway 版本与源库一致后删除临时库，不触碰线上库：

```powershell
docker compose exec backup bash /scripts/restore-drill.sh
```

## 端口

- Core MySQL：`127.0.0.1:3307`
- Commerce MySQL：`127.0.0.1:3309`
- Core Redis：`127.0.0.1:6380`
- Commerce Redis：`127.0.0.1:6381`
- Kafka：`127.0.0.1:19092` / `19093` / `19094`（三个节点各自对宿主机通告的地址）
- Kafka UI（可选）：`127.0.0.1:18082`

所有 Compose 端口默认只绑定本机。

Java 服务的 Working directory 设为仓库根目录后会自动加载 `.env`，并连接上述宿主机端口。源码默认值已对齐 3307/3309、6380/6381 和 18081。Vue 开发服务器运行在 `5173`，并把 API 代理到本地 Core `8081`。

本地 Kafka 默认启用：Commerce Outbox Relay 与 Core 的事件消费者连接三个节点，Redis Stream 继续负责秒杀准入后的本地订单消费。只有在明确诊断 MQ 故障时才临时设置 `CITYCOPILOT_KAFKA_ENABLED=false`，并保留所有未发布 Outbox。

## 常用命令

```powershell
# 查看状态
.\tools\citycopilot.ps1 status

# 查看日志
.\tools\citycopilot.ps1 logs -Service redis

# 重启基础设施
.\tools\citycopilot.ps1 restart

# 停止并保留数据卷
.\tools\citycopilot.ps1 stop

# 删除 MySQL、Docker Redis 和上传文件卷
.\tools\citycopilot.ps1 reset -Force
```

不要提交 `.env`。正式部署应使用 Docker secrets 或外部密钥管理。

## 排障

- Docker daemon 不可用：启动 Docker Desktop 后重试。
- 本地 Java 服务无法连接 Redis：确认 `redis`、`commerce-redis` 为 `healthy`，IDE Working directory 是仓库根目录；Core 连 `127.0.0.1:6380`，Commerce 连 `127.0.0.1:6381`。
- 本地 Java 服务无法连接 MySQL：确认使用对应的 3307/3309 端口，并加载根 `.env` 中对应账号密码。
- Java 服务提示 8081/8083 被占用但系统查不到进程：这是已移除应用容器遗留的 Docker Desktop loopback 代理，执行 `docker desktop restart` 后再启动。
- 业务表不存在或迁移任务失败：按 [数据库迁移运行手册](database-migrations.md)检查对应任务，不要手工导入旧初始化 SQL。
- Outbox 已发布但消费缓慢：同时检查 Broker 的 `pop.log`、消费积压和直接内存错误；Broker 的堆、直接内存与容器总预算集中在 [Compose](../compose.yaml)，调整容器上限时要一起核对 JVM 预算。
- 前端地图配置：本地 Vite 读取 `frontend/.env`，不再依赖 Docker 前端镜像。
- 附近店铺无法点击：高德外部地点只用于发现展示，不能下单。页面会标明“外部地点 · 暂不支持下单”；附近没有入驻店铺时，点击“查看可下单店铺”回到本地交易店铺。

## 隔离多实例验证

[`redesign-compose.yaml`](../tools/loadtest/redesign-compose.yaml)与默认文件组合使用；独立项目创建自己的数据卷和 `*_load_test` 数据库，不复用日常业务数据。两个 Core、两个 Commerce 共用现有 nginx，服务之间也通过 nginx 分发请求。需使用支持 `!override` 的 Docker Compose。

```powershell
docker compose -p citycopilot-redesign-load -f compose.yaml -f tools/loadtest/redesign-compose.yaml --profile app up -d --build --scale core=2 --scale commerce=2 --wait
python tools/loadtest/run_multiinstance_acceptance.py --failover --output tools/loadtest/results/redesign/topology.json
python tools/loadtest/run_redesign_load.py --users 300 --concurrency 50 --read-requests 3000 --faults --output tools/loadtest/results/redesign/load.json
```

前台入口是 `127.0.0.1:18090`；`18092`、`18094` 是仅绑定本机的 Core、Commerce 压测入口，用来分别观察后端容量和前台 IP 限流。脚本在写入前核对项目、卷和两个数据库名称，只追加本轮数据。CPU 采样、逐请求结果和失败阶段保存在输出文件旁；故障模式会停止或暂停该独立项目的指定容器，并在结束时恢复。

`run_multiinstance_acceptance.py` 检查实际负载分发及任务持有者接管。业务脚本检查秒杀、80% 热点读、预约容量、两个关单任务，以及 Stream、Outbox、Redis 故障；Redis 重启后额外删除本轮券的库存键，以验证带证据哈希的修复。Core Inbox 的重复投递以实际 duplicate 指标增加和原处理时间不变为证据。性能数字只使用当次真实产物更新 [E6](evidence/2026-07-26-e6-performance-and-recovery.md)。

缓存对照只用本轮 key 的 `GET`、`EVAL`、`EVALSHA` 计数，MONITOR 原文不会保存。店铺 L1 与等量 Redis GET 比较，并用同一段 HTTP 实际速率计算 Redis QPS 降幅；售罄缓存与“每次请求前广播失效”的串行对照比较每请求 Lua 次数，广播和测量屏障不计入。TTL 仍生效，目标 key 的后台读取保留在计数中；这些受控差值不能解释为生产峰值。

仅复验 Stream、Outbox、Redis 故障时可使用 `--faults-only` 并指定另一输出文件，原报告不会被覆盖。停机期间结果未知的请求沿用原幂等键重放，尝试结果也写入报告。

停止时保留独立数据卷：

```powershell
docker compose -p citycopilot-redesign-load -f compose.yaml -f tools/loadtest/redesign-compose.yaml --profile app down
```

## 本地秒杀压测

先启动 Docker 基础设施，并在 IDE 中启动 Commerce（8083）和 Core（8081）。默认本地模式同时启用 Redis Stream 订单消费和 Kafka Outbox Relay：

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

Kafka Relay 默认开启；报告会等待并核对本轮 Outbox 发布结果。若此前在 IDEA Run Configuration 中显式设置过 `CITYCOPILOT_KAFKA_ENABLED=false`，应删除该覆盖或改为 `true`，重启 Commerce 后再执行：

```powershell
.\tools\loadtest\run_jmeter_commerce.ps1 -Users 5000 -Threads 200 -RequireOutboxDrain
```

`-BrokerOutage` 用于“停掉全部 Kafka 节点压测后恢复并等待 Outbox 清零”的故障恢复场景，同样要求 Commerce 已启用 Kafka Relay。

## 架构边界

日常开发的业务进程由 IDE 管理；`app` profile 和独立负载配置负责容器验证。Docker Redis 是项目的隔离运行依赖，不是对 Windows 或 WSL Redis 的重新安装。
