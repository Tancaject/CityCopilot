# Commerce 所有权与运行门禁

## 当前所有权

Commerce 是优惠券、秒杀准入、订单、Reservation、恢复记录和业务 Outbox 的唯一数据 owner。Core 保留外部兼容 API、权限、审计与 Tool 权威入口，并通过版本化 `CommercePortClient` 调用 Commerce；Commerce 不可用时交易失败关闭。

旧 Core 交易 writer、Redisson 锁、旧 Redis Stream/Lua、旧 Outbox Relay、旧交易表初始化和迁移回退工具已经从仓库物理删除。不存在可通过 Spring profile 或环境变量重新启用的本地 writer。

## 后台读取边界

[库存初始化](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceRedisInventoryBootstrap.java)、[活动元数据初始化](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceRedisCampaignMetadata.java)和[一致性巡检](../../backend/commerce-service/src/main/java/com/citycopilot/commerce/CommerceReconciliationJob.java)先固定本轮最大主键，再按主键游标分批读取，不一次装入全库券列表。库存初始化只判断是否存在订单、预留或恢复历史，不统计全部历史行；发现任何历史仍保持准入关闭并交给既有修复流程。巡检仍在一轮内处理上界以内的全部券，分页不会重置单券异常连续次数或改变修订/fence 顺序。

分批限制的是每次 SQL 返回规模，不代表整轮巡检耗时或单券证据大小已有上限。一致性修复需要完整买家与恢复证据，不能截断后仍宣称一致；对外全量列表也不能静默改为只返回首批。订单按页接口继续使用既有页码契约；引入游标接口应作为兼容的独立变更。历史订单已由 V3 迁移回填为 `ORDER_CREATED` 账本，准入同步路径不再查询订单表；Worker 落单时仍会把与订单身份一致、但处于 `REJECTED`/`REPAIR_REQUIRED` 或缺失的账本纠正为 `ORDER_CREATED`。

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
旧 Stream、pending 和正在处理的消息全部归零，再停止 worker，并按
[数据库迁移运行手册](../database-migrations.md#已有库首次接管)显式接管后执行 Commerce 迁移任务。
[admission ledger 迁移](../../deploy/mysql/commerce/V3__admission_ledger.sql)会把每条非空
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
旧 Stream/PEL；停止旧 worker并由 Flyway 执行 ledger 建表、回填和 postflight；全量替换所有旧
API/worker；最后才重新开放入口。
DDL 采用 expand/contract 只说明新旧进程可短暂连接同一扩展 schema，不代表旧准入
语义可以与 ledger 版本并行服务。

### 修改 Redis 键空间配置

`CITYCOPILOT_COMMERCE_REDIS_ROUTING_GENERATION` 与 `CITYCOPILOT_COMMERCE_REDIS_PHYSICAL_SHARD_COUNT` 写入每个 Commerce Redis 键名和准入账本，所有实例必须一致。修改它们是维护窗口内的数据迁移，没有在线切换：

1. 关闭秒杀入口（`CITYCOPILOT_COMMERCE_API_ENABLED=false` 或从网关摘除），保留 Worker 与 Relay 运行。
2. 等待以下检查全部为零：
   - 每条 `commerce:{commerce:<generation>:<count>:<shard>}:stream:orders` 的 pending、consumer lag 与单券 in-flight；
   - `commerce_admission_ledger` 中 `ACCEPTED`、被认领或 `REPAIR_REQUIRED` 的记录；
   - `commerce_order_recovery` 未终态的恢复阶段、未重放 DLQ、处理中的 Reservation；
   - `commerce_outbox_event` 中待发布、已认领或失败的事件。
3. 停止全部实例，迁移库存、buyer、活动元数据和全局幂等键到新键名并逐券核对，再以新配置整体启动。

旧配置留下的 `PREPARED` 账本不会被搬迁：同一幂等键重试会得到 `COMMERCE_ADMISSION_ROUTING_CONFLICT`（503），客户端需换新键重新下单。在线扩容的设计记录在 [ADR-0009](../adr/0009-commerce-high-concurrency-admission.md#未实现在线分片扩容)，当前未实现。

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
