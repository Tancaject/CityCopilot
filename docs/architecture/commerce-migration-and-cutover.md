# Commerce 所有权与运行门禁

## 当前所有权

Commerce 是优惠券、秒杀准入、订单、Reservation、恢复记录和业务 Outbox 的唯一数据 owner。Core 保留外部兼容 API、权限、审计与 Tool 权威入口，并通过版本化 `CommercePortClient` 调用 Commerce；Commerce 不可用时交易失败关闭。

旧 Core 交易 writer、Redisson 锁、旧 Redis Stream/Lua、旧 Outbox Relay、旧交易表初始化和迁移回退工具已经从仓库物理删除。不存在可通过 Spring profile 或环境变量重新启用的本地 writer。

## 完整性门禁

运行：

```powershell
.\tools\migrations\verify-commerce-integrity.ps1
```

脚本只读取当前 `citycopilot_commerce` 数据库，验证：

- 十一张必需的 Commerce 表均存在（包括 `commerce_admission_ledger` 与
  `commerce_id_segment`），且 order/voucher 两个 ID namespace 已初始化；
- `commerce_seckill_voucher` 不存在负库存。

该门禁不读取 Core 数据库，也不承担历史数据迁移对账。

## 排空门禁

### 首次 admission ledger 升级

从无 ledger 的历史版本升级时，先关闭所有新秒杀准入，保留旧 Stream worker 直到
旧 Stream、pending 和正在处理的消息全部归零，再停止 worker 并执行
`deploy/mysql/commerce/003-commerce-admission-ledger.sql`。迁移会把每条非空
`idempotency_key` 的既有订单，以相同 order/user/voucher/key 和
`seckill:v1:<voucherId>` fingerprint 回填为 `ORDER_CREATED`；仅回填记录使用
`legacy-order-v0/0` sentinel，后台恢复不会认领它。订单与已有 ledger 身份有任何
冲突时迁移直接失败，禁止用 `INSERT IGNORE` 跳过。只有 postflight 证明每条历史
订单都有完全一致的终态 ledger、并且所有旧 API/worker 镜像均已替换后，才能重新
开放准入。首次 ledger rollout 是 maintenance cutover，不支持旧 writer 与新 writer
混合滚动。无 ledger 的在途消息不能仅凭 MySQL 安全推断，因此“先停 admission、
再排空旧 Stream”是强制升级门禁。幂等键全链路按 opaque、大小写敏感处理；生产
invariants migration 在校验列长度与唯一索引形状后，将 `commerce_order` 和
`commerce_order_recovery` 的 `idempotency_key` 统一为 `utf8mb4_bin`。

这次发布不能做旧/新镜像混跑的普通 rolling：旧镜像不会写 admission ledger。
维护顺序固定为：在网关封闭旧秒杀入口并把旧 API scale 到 0；保留旧 worker 排空
旧 Stream/PEL；停止旧 worker并执行 003 建表、回填和 postflight；全量替换所有旧
API/worker；初始化并核验 routing control 与每 shard gate；最后才重新开放入口。
DDL 采用 expand/contract 只说明新旧进程可短暂连接同一扩展 schema，不代表旧准入
语义可以与 ledger 版本并行服务。

生产实例的 `CITYCOPILOT_COMMERCE_ROUTING_CONTROL_AUTO_INITIALIZE` 必须为
`false`。首次启用路由栅栏时，由运维身份显式初始化当前 fingerprint；任意实例
都不能通过启动竞态选择 generation：

```powershell
.\tools\migrations\commerce-runtime-drain-gate.ps1 `
  -Action Initialize -Generation v1 -PhysicalShardCount 16
```

每次秒杀准入先读取共享 control key，并在券所在 Redis Cluster slot 的 Lua 内
再次校验 shard gate。切换协议固定为：

```powershell
# 1. CAS ACTIVE(v1/16) -> DRAINING(v1/16)，同时关闭 16 个同槽 gate
.\tools\migrations\commerce-runtime-drain-gate.ps1 `
  -Action BeginDrain -Generation v1 -PhysicalShardCount 16

# 2. 保留旧 worker/recovery 运行；先让旧 API 退出流量并等待在途 HTTP 请求结束，
#    再重复检查直到全部积压归零
.\tools\migrations\commerce-runtime-drain-gate.ps1 `
  -Action VerifyDrain -Generation v1 -PhysicalShardCount 16

# 3. 完成并独立核验业务 key 数据迁移后，再做最终排空检查和 CAS 激活
.\tools\migrations\commerce-runtime-drain-gate.ps1 `
  -Action Activate -Generation v1 -PhysicalShardCount 16 `
  -TargetGeneration v2 -TargetPhysicalShardCount 32
```

`DRAINING` 拒绝新 seckill 和人工 DLQ replay，但旧 Stream worker 继续消费，
durable admission recovery 也可在旧 fingerprint 的 DRAINING gate 下恢复已接受命令。
激活以后，旧 API 实例的全局 fingerprint 和旧 shard gate 都不匹配，因此请求路径
fail closed；这不是依赖实例启动时的一次性校验。

`VerifyDrain` 和 `Activate` 都先执行完整性门禁，再按指定 generation/count 枚举
所有 shard Stream 和 in-flight key，并要求：

- `commerce_outbox_event` 没有待发布、已认领或失败事件；
- 没有仍处于处理中或待释放的 Reservation；
- 没有未重放 DLQ；
- `commerce_order_recovery` 没有未解决恢复阶段；
- 每条 `commerce:{commerce:<generation>:<count>:<shard>}:stream:orders` 的
  pending、consumer lag 和该 shard 单券 in-flight 总数均为零；
- durable admission ledger 没有仍待恢复、被认领或要求人工修复的记录；
- 全局业务 mutation lease、每个物理 shard 的 admission lease 与活动元数据更新
  fence 均为零。过期 lease 以 Redis `TIME` 清理，不依赖实例本地时钟。

Ledger 的 `PREPARED` 只代表尚未向客户端返回 202 的身份预留，不阻断业务排空，
但不能原样遗留在旧 fingerprint。`Activate` 在最终 CAS 前及 CAS 后都以
`status='PREPARED' + source generation/count` 为条件，把它们幂等改写到目标
generation/count；orderId、user、voucher、幂等键、fingerprint 和状态均保持不变。
因此客户端重试可在新 generation 继续使用同一 orderId，并建立目标 generation 的
全局幂等键。`ACCEPTED`、活动 claim 或 `REPAIR_REQUIRED` 必须归零，因为它们代表
已经确认但尚未完成的业务效果。

每个准入请求在写 ledger 前，先用券所在 Redis slot 的 Lua 原子校验 shard gate 并
登记一个可续租 admission lease；prepare、全局幂等、准入 Lua、ledger 终态确认和
稳定拒绝都在该 lease 内完成。`BeginDrain` 先关闭 control 与全部 shard gate，
`VerifyDrain`/`Activate` 再要求所有 shard admission lease 归零，因此不会在最终
PREPARED 搬迁后出现迟到的 source `PREPARED`。优惠券管理、订单状态变更、人工修复
等写路径使用全局并发 mutation lease；operator 取得独占 lease 后，新的业务 lease
无法进入，并等待既有 lease 释放或过期。已提交 CAS 的重跑会先把混合状态的目标
gate 全部重新关闭，等待目标 admission lease 归零，再执行完整 target preflight、
重新确认 control 复制和 PREPARED 搬迁；不会重新开放旧 generation。仓库仍建议在
最终切换窗口从服务发现移除旧 API，作为减少在途时间的运维措施，而不是正确性前提。

`BeginDrain` 和 `Activate` 会修改 control/gate；`VerifyDrain` 只读。`Activate`
只额外迁移上述 `PREPARED` ledger fingerprint，不会迁移库存、buyer、一人一券、
活动元数据或全局幂等记录。改变 generation/count 前必须
另行完成这些业务 key 的可审计迁移；`Activate` 会在 CAS 前逐券核对目标 stock、
metadata、buyer 集合，并确认目标 Stream/in-flight 为空，任何空 keyspace 或漂移都会
fail closed。“排空为零”不能替代数据迁移。
Redis Cluster 主从切换、脚本 cross-slot、实例强制终止和迁移中断恢复仍必须在真实集群验收。
仓库中的 PowerShell 实现通过 Docker Compose 执行 MySQL/Redis 命令，是本地演练和
协议参考；它没有分布式 operator lease 与 node-bound `WAIT`，禁止连接生产环境，
也禁止和 Java operator 并行执行。受控迁移时应把
`commerce.routingOperator` 作为一次性 Java 进程或容器单独运行；
它复用 Commerce 制品中的 Java/Lettuce operator，在目标 key 所在当前 master 的同一
物理连接上执行 Lua CAS/SET 与 `WAIT`，可沿用 Redis Cluster seed、TLS 和 ACL 配置。
该进程使用独立受限的 MySQL/Redis 运维身份，业务实例不接收这些控制面凭据。

## 故障与恢复边界

- RocketMQ 故障时保留 Commerce Outbox，恢复 Proxy 后由 `CommerceOutboxRelay` 继续发布。
- Redis Stream 或订单事务异常时，由 `commerce_order_recovery`、补偿/恢复 Lua 和 DLQ 保持可恢复状态。
- 生产准入启用 all-replica confirmation：同一 master connection 在写前读取完整
  `CLUSTER REPLICAS`（启动时先验证 provider 能力），写后 `WAIT` 所有已配置副本，
  并复核 topology fingerprint。
  任一配置副本离线、拓扑不可见或确认期间变化都 fail closed；Redis ACL 必须显式
  允许该拓扑命令。这只覆盖所有可晋升副本已确认后的一次 master 故障，不等价于
  双故障、供应商控制面灾难或持久化恢复证明，仍需真实集群注入和 fence/rebuild 验收。
- admission/runtime lease 依靠 Redis TTL 与共享心跳回收失联进程；写路径在持久化
  操作前和事务 commit 前复核所有权。若 JVM 停顿或 Redis 网络隔离持续超过 TTL，
  已过期进程恢复后仍不是跨 MySQL/Redis 的存储层 fencing token。生产必须把 TTL
  设为显著高于最大事务、GC 停顿和网络超时，并通过超时故障注入验证；不能把本轮
  进程级 lease 宣称为任意时长分区下的线性化证明。
- 正常秒杀至少包含分片 lease、全局幂等与准入 Lua 三次 all-replica confirmation；
  release 是保守的单 key best-effort Lua，失败时由 TTL 阻塞切换而不会误放行。最终
  吞吐、Redis CPU/网络和 `CLUSTER REPLICAS` 开销必须在真实拓扑压测后定额。
- Core 的 `processed_event` 与 `event_projection_version` 是当前 RocketMQ Inbox/版本审计表，不属于旧交易链路。
- 不提供切回旧 Core writer 的运行时回滚。若必须恢复历史版本，应在隔离环境部署历史制品，并执行单独的数据恢复方案。

## 已有数据卷

删除仓库代码和 SQL 不会删除已有 Docker volume 中的历史表或 Redis key。清理历史数据必须作为独立运维变更执行：先停止历史进程、备份、核对 Commerce 数据和未完成消息，再对明确目标执行定向删除。不得用 `reset -Force` 或 `FLUSHDB` 代替定向清理，也不得删除当前 Core Inbox 表或任何 `commerce_*` 表。
